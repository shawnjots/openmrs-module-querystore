/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.bridge;

import java.lang.reflect.Method;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.BaseOpenmrsData;
import org.openmrs.api.context.Context;
import org.openmrs.module.querystore.serialization.ClinicalRecordSerializer;
import org.springframework.aop.AfterReturningAdvice;

/**
 * Shared template for the AOP migration bridge advice (ADR Decision 12 "Migration bridge"). Each
 * concrete subclass adapts the template to one core service's save / void / unvoid / purge
 * methods.
 *
 * <p>The contract a subclass must declare:
 * <ul>
 *   <li>{@link #getSupportedType()} — the entity class used to filter {@code returnValue} and
 *       {@code args[0]} via {@code instanceof}, so an aspect on a service that handles multiple
 *       entity types (e.g., {@code OrderService} for drug / test / referral orders) processes
 *       only its own subtype.</li>
 *   <li>{@link #serializer()} — the {@link ClinicalRecordSerializer} for the type. Subclasses
 *       typically resolve a Spring bean via {@link Context#getRegisteredComponent}, so the advice
 *       instance can be no-arg constructed by OpenMRS at module load.</li>
 *   <li>{@link #triggerMethods()} — the set of advised method names. Core's naming is uneven
 *       ({@code save}/{@code saveX}/{@code removeAllergy}) so this is per-subclass rather than a
 *       hard-coded convention.</li>
 *   <li>{@link #purgeMethods()} — the subset of triggers whose semantics are "remove from core
 *       unconditionally," which the advice routes straight to delete regardless of the entity's
 *       voided flag.</li>
 * </ul>
 *
 * <p><b>Projection is delegated, and shared with the events consumer.</b> This advice only decides
 * <em>whether</em> to fire (a trigger method) and whether the call is a purge; the actual
 * {@code serialize → partition-by-voided → dispatch} work — and the per-type behaviour (obs group
 * flattening, patient purge sweep) now carried on the {@link ClinicalRecordSerializer} — lives in
 * {@link RecordProjector}, which the events consumer drives identically. That shared core is what
 * makes events/AOP parity (ADR Decision 12) hold by construction. Serialization runs here, inside
 * the originating transaction (lazy navigations resolve against an open session); embed + write run
 * after commit on the {@link AfterCommitDispatcher}.
 *
 * <p><b>Sync-mode gate.</b> {@link #afterReturning} short-circuits when the bridge is gated off by
 * the {@code querystore.syncMode} global property (ADR Decision 12, "Runtime sync-mode selection")
 * — checked via {@link #aopEnabled()} after the cheap trigger-method test, so non-trigger calls pay
 * nothing. The configured default is now {@code events} (so the bridge is normally gated off); it
 * falls back to AOP-on only when the mode can't be resolved (e.g. no running context) — the
 * failure-safe, since the bridge is always available.
 *
 * <p><b>Removal marker.</b> Each subclass is time-bound and carries its own removal marker per
 * ADR Decision 12. This abstract base is deleted when the last subclass is removed.
 */
public abstract class AbstractIndexingAdvice<T extends BaseOpenmrsData> implements AfterReturningAdvice {

	private final Log log = LogFactory.getLog(getClass());

	@Override
	public final void afterReturning(Object returnValue, Method method, Object[] args, Object target) {
		String name = method.getName();
		if (!triggerMethods().contains(name)) {
			return;
		}
		// Trigger method confirmed; only now consult the sync-mode gate (a registered-component
		// lookup) so non-trigger calls on the advised service — reads, mostly — pay nothing. When
		// querystore.syncMode gates the bridge off (events / events-only), skip. ADR Decision 12,
		// "Runtime sync-mode selection." The core-events consumer is gated symmetrically.
		if (!aopEnabled()) {
			return;
		}

		T entity = entityFrom(returnValue, args);
		if (entity == null) {
			return;
		}

		try {
			// Shared with the events consumer (ADR Decision 12 parity): the projection algorithm and
			// per-type behaviour live in RecordProjector + the serializer, not here. This advice only
			// decides "fire, and is this a purge?" from the method name; the entity's own voided flag
			// drives index-vs-delete inside the projector.
			RecordProjector.project(serializer(), entity, purgeMethods().contains(name), indexer(),
			    dispatcher());
		}
		catch (RuntimeException e) {
			// Best-effort per ADR Decision 12. Failures during serialization or dispatch must not
			// propagate back to the clinical-thread caller (the originating save already succeeded).
			log.warn(getClass().getSimpleName() + " failed for " + name + "; swallowing per ADR Decision 12", e);
		}
	}

	private T entityFrom(Object returnValue, Object[] args) {
		Class<T> type = getSupportedType();
		if (type.isInstance(returnValue)) {
			return type.cast(returnValue);
		}
		if (args != null && args.length > 0 && type.isInstance(args[0])) {
			return type.cast(args[0]);
		}
		return null;
	}

	/**
	 * Subclass hook: which Java class to match against {@code returnValue} and {@code args[0]} when
	 * extracting the entity. For services that handle multiple subtypes ({@code OrderService}) this
	 * is the specific subtype the advice cares about.
	 */
	protected abstract Class<T> getSupportedType();

	protected abstract ClinicalRecordSerializer<T> serializer();

	protected abstract Set<String> triggerMethods();

	/**
	 * Subset of {@link #triggerMethods()} whose semantics are "remove from core unconditionally."
	 * The advice routes these straight to delete regardless of the entity's voided flag. Must be
	 * non-empty for every advised core service in OpenMRS 2.x — every type has at least one purge
	 * method.
	 */
	protected abstract Set<String> purgeMethods();

	/**
	 * Whether the AOP bridge path is active under the configured {@code querystore.syncMode} (ADR
	 * Decision 12). Read from the cached {@link SyncModeResolver}. If the resolver can't be reached —
	 * notably in plain unit tests with no running OpenMRS context — the gate defaults to {@code true}
	 * (AOP enabled), the safe fallback that preserves pre-gate behavior and keeps the projection from
	 * silently stalling. Package-visible so gate tests can drive the gated-off path without a context.
	 */
	boolean aopEnabled() {
		try {
			return Context.getRegisteredComponent("querystore.syncModeResolver", SyncModeResolver.class)
			        .current().aopEnabled();
		}
		catch (RuntimeException e) {
			log.debug("SyncModeResolver unavailable; defaulting AOP bridge to enabled", e);
			return true;
		}
	}

	BridgeIndexer indexer() {
		return Context.getRegisteredComponent("querystore.bridge.indexer", BridgeIndexer.class);
	}

	AfterCommitDispatcher dispatcher() {
		return Context.getRegisteredComponent("querystore.bridge.dispatcher", AfterCommitDispatcher.class);
	}
}
