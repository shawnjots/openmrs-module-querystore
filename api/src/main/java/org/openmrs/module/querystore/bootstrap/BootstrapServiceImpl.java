/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.bootstrap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.embedding.EmbeddingProvider;
import org.openmrs.module.querystore.serialization.ClinicalRecordSerializer;
import org.openmrs.module.querystore.spi.ResourceTypeNames;
import org.openmrs.module.querystore.spi.ResourceTypeProvider;

/**
 * Default {@link BootstrapService}: registers a {@link TypeBootstrapper} per resource type at
 * Spring wiring time and dispatches {@link #bootstrap(String)} to the matching one. Bootstrappers
 * run sequentially per type — embedding is CPU-bound and concurrency belongs in the
 * {@link EmbeddingProvider}, not the dispatch loop.
 *
 * <p>Module-provided bootstrappers (ADR Decision 13) are picked up via
 * {@link ResourceTypeProvider} beans discovered from the global OpenMRS Spring context at
 * {@link #bootstrap()} / {@link #bootstrap(String)} time, so a providing module installed after
 * querystore is included without restart. Providers run after all core types because core obs
 * typically dwarfs every other type; deferring providers keeps them from being delayed behind a
 * long-running core scan only when the provider corpus is small, which is the common case.
 */
public class BootstrapServiceImpl extends BaseOpenmrsService implements BootstrapService {

	private static final Log log = LogFactory.getLog(BootstrapServiceImpl.class);

	private final Map<String, TypeBootstrapper<?>> bootstrappers = new LinkedHashMap<>();

	// Per-resource-type lock so concurrent bootstrap(type) calls serialize. Without it, two
	// callers (admin + scheduled job, or double-click) share the same progress row and trample
	// each other's cursor and counts. Document storage stays correct via Slice A's version
	// protection, but the progress bookkeeping silently corrupts.
	private final Map<String, Object> typeLocks = new ConcurrentHashMap<>();

	private BootstrapProgressDao progressDao;

	private QueryStoreService queryStoreService;

	private EmbeddingProvider embeddingProvider;

	// Test override: when non-null, replaces the Spring-context discovery path so tests can pin
	// the provider set without a live OpenMRS context. Normal runtime leaves this null and
	// discovers via Context.getRegisteredComponents on each bootstrap call.
	private List<ResourceTypeProvider> providersOverride;

	public void setProgressDao(BootstrapProgressDao progressDao) {
		this.progressDao = progressDao;
	}

	public void setQueryStoreService(QueryStoreService queryStoreService) {
		this.queryStoreService = queryStoreService;
	}

	public void setEmbeddingProvider(EmbeddingProvider embeddingProvider) {
		this.embeddingProvider = embeddingProvider;
	}

	public void setBootstrappers(List<TypeBootstrapper<?>> list) {
		bootstrappers.clear();
		if (list != null) {
			for (TypeBootstrapper<?> b : list) {
				bootstrappers.put(b.getResourceType(), b);
			}
		}
	}

	/** Test hook to inject providers without a live OpenMRS Spring context. Validation happens at
	 *  discovery time (per-provider, with skip-on-failure isolation) so this seam can also be used
	 *  to wire in deliberately-malformed providers when exercising the isolation behavior. */
	void setProvidersOverride(List<ResourceTypeProvider> providers) {
		this.providersOverride = providers;
	}

	@Override
	public void bootstrap() {
		Map<String, ResourceTypeProvider> providers = discoverProviders();
		for (String resourceType : allResourceTypes(providers)) {
			try {
				runOne(resourceType, providers);
			}
			catch (RuntimeException e) {
				log.warn("Bootstrap of " + resourceType + " failed; continuing with remaining types", e);
			}
		}
	}

	@Override
	public void bootstrap(String resourceType) {
		runOne(resourceType, discoverProviders());
	}

	@Override
	public List<BootstrapProgress> getStatus() {
		return progressDao.findAll();
	}

	@Override
	public BootstrapProgress getStatus(String resourceType) {
		return progressDao.find(resourceType);
	}

	private void runOne(String resourceType, Map<String, ResourceTypeProvider> providers) {
		TypeBootstrapper<?> bootstrapper = bootstrappers.get(resourceType);
		if (bootstrapper == null) {
			ResourceTypeProvider provider = providers.get(resourceType);
			if (provider == null) {
				throw new IllegalArgumentException("No bootstrapper registered for resource type: " + resourceType);
			}
			bootstrapper = provider.getBootstrapper();
			if (bootstrapper == null) {
				// Provider exists but declared no historical backfill — surface the no-op so an
				// admin who explicitly invoked bootstrap("foo") sees there's nothing to do for
				// that name, distinct from "no such type."
				throw new IllegalArgumentException(
				        "Provider for resource type '" + resourceType
				                + "' declared no bootstrapper (no historical backfill)");
			}
		}
		Object lock = typeLocks.computeIfAbsent(resourceType, k -> new Object());
		synchronized (lock) {
			BootstrapProgress progress = progressDao.find(resourceType);
			if (progress == null) {
				progress = new BootstrapProgress(resourceType);
			}
			bootstrapper.run(progress, queryStoreService, embeddingProvider, progressDao);
		}
	}

	/** Core types first (insertion order), then providers with a non-null bootstrapper. The map is
	 *  a LinkedHashMap so provider insertion order is preserved. */
	private List<String> allResourceTypes(Map<String, ResourceTypeProvider> providers) {
		List<String> types = new ArrayList<>(bootstrappers.keySet());
		for (Map.Entry<String, ResourceTypeProvider> e : providers.entrySet()) {
			if (e.getValue().getBootstrapper() == null) {
				continue;
			}
			if (!types.contains(e.getKey())) {
				types.add(e.getKey());
			}
		}
		return types;
	}

	/** Returns providers keyed by validated name, deduplicated, with bad ones logged and skipped.
	 *  Source is the test override when set, otherwise the OpenMRS Spring context. */
	private Map<String, ResourceTypeProvider> discoverProviders() {
		List<ResourceTypeProvider> discovered = providersOverride;
		if (discovered == null) {
			try {
				discovered = Context.getRegisteredComponents(ResourceTypeProvider.class);
			}
			catch (NullPointerException noContext) {
				// Direct construction without a wired Spring context (test environments, and the
				// narrow window during module activation before context refresh completes) lands
				// here. A real provider-bean misconfiguration surfaces as BeansException instead
				// and propagates.
				return Collections.emptyMap();
			}
		}
		if (discovered == null || discovered.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<String, ResourceTypeProvider> accepted = new LinkedHashMap<>(discovered.size());
		Set<String> seen = new HashSet<>(discovered.size());
		for (ResourceTypeProvider p : discovered) {
			String name = null;
			try {
				name = p.getResourceType();
				ResourceTypeNames.validateProvided(name);
				if (!seen.add(name)) {
					throw new IllegalStateException("Duplicate provider for resource type '" + name + "'");
				}
				ClinicalRecordSerializer<?> ser = p.getSerializer();
				if (ser != null && !name.equals(ser.getResourceType())) {
					throw new IllegalStateException("Provider declares resource type '" + name
					        + "' but its serializer reports '" + ser.getResourceType()
					        + "' — names must match or routing drifts between bootstrap and bridge paths");
				}
				accepted.put(name, p);
			}
			catch (RuntimeException e) {
				log.warn("Skipping malformed ResourceTypeProvider bean " + p.getClass().getName()
				        + (name != null ? " (resource type '" + name + "')" : "")
				        + " — " + e.getMessage());
			}
		}
		return accepted;
	}

	// Package-private read-only view for tests asserting registration without exercising run().
	Map<String, TypeBootstrapper<?>> getBootstrappers() {
		return Collections.unmodifiableMap(bootstrappers);
	}
}
