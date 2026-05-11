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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.embedding.EmbeddingProvider;

/**
 * Default {@link BootstrapService}: registers a {@link TypeBootstrapper} per resource type at
 * Spring wiring time and dispatches {@link #bootstrap(String)} to the matching one. Bootstrappers
 * run sequentially per type — embedding is CPU-bound and concurrency belongs in the
 * {@link EmbeddingProvider}, not the dispatch loop.
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

	@Override
	public void bootstrap() {
		for (String resourceType : bootstrappers.keySet()) {
			try {
				runOne(resourceType);
			}
			catch (RuntimeException e) {
				log.warn("Bootstrap of " + resourceType + " failed; continuing with remaining types", e);
			}
		}
	}

	@Override
	public void bootstrap(String resourceType) {
		runOne(resourceType);
	}

	@Override
	public List<BootstrapProgress> getStatus() {
		return progressDao.findAll();
	}

	@Override
	public BootstrapProgress getStatus(String resourceType) {
		return progressDao.find(resourceType);
	}

	private void runOne(String resourceType) {
		TypeBootstrapper<?> bootstrapper = bootstrappers.get(resourceType);
		if (bootstrapper == null) {
			throw new IllegalArgumentException("No bootstrapper registered for resource type: " + resourceType);
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

	// Package-private read-only view for tests asserting registration without exercising run().
	Map<String, TypeBootstrapper<?>> getBootstrappers() {
		return Collections.unmodifiableMap(bootstrappers);
	}
}
