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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Encounter;
import org.openmrs.api.context.Context;
import org.openmrs.module.querystore.model.QueryDocument;
import org.openmrs.module.querystore.serialization.EncounterRecordSerializer;
import org.springframework.aop.AfterReturningAdvice;

/**
 * Migration-bridge advice on {@link org.openmrs.api.EncounterService} (ADR Decision 12 "Migration
 * bridge"). Projects each {@code saveEncounter / voidEncounter / unvoidEncounter / purgeEncounter}
 * call through the same {@code serialize → embed → index} pipeline the events-first handlers will
 * drive once core publishes encounter events.
 *
 * <p><b>Removal marker.</b> Delete this class — and its {@code <advice>} entry in
 * {@code omod/src/main/resources/config.xml} — when the events-first subscriber for encounters
 * ships and has been verified at parity. ADR Decision 12 requires the issue ID for that subscriber
 * to be cited here; until the issue is filed at aspect-merge time it stays as {@code TBD}:
 * <pre>Removal trigger: TBD (events-first encounter subscriber)</pre>
 *
 * <p><b>Obs cascade is handled by the obs advice, not here.</b> {@code voidEncounter} and
 * {@code purgeEncounter(encounter, true)} iterate the encounter's obs and route each through
 * {@code Context.getObsService().voidObs / purgeObs} — those calls re-enter Spring AOP and fire
 * {@link ObsIndexingAdvice}. This advice therefore only projects the encounter document itself;
 * the cascade-delete gap the ADR accepts for the bridge window is limited to obs that bypass
 * {@code ObsService} entirely (e.g., new obs persisted via Hibernate cascade when
 * {@code saveEncounter} runs).
 *
 * <p><b>{@code transferEncounter} is not covered.</b> {@code EncounterServiceImpl.transferEncounter}
 * invokes {@code voidEncounter} and {@code saveEncounter} on {@code this} via direct
 * {@code invokevirtual} rather than through {@code Context.getEncounterService()}, so the inner
 * calls do not re-enter Spring AOP. The original encounter therefore stays in the read store and
 * the copied encounter is not projected until the bootstrap reconciles. This is the same "AOP
 * misses self-cascade" shape ADR Decision 12 accepts for the bridge window; reconciliation under
 * the Sync-reliability open question is the long-term fix.
 *
 * <p>The per-node voided policy from {@link ObsIndexingAdvice} reduces to a per-root check here:
 * a voided encounter routes to delete regardless of which method fired (UI "void this encounter"
 * flows that set the flag and resave), non-voided routes to index. {@code purgeEncounter} bypasses
 * the policy and unconditionally deletes.
 *
 * <p>Per OpenMRS module conventions, advice instances are constructed by the framework via
 * reflection from a {@code <advice>} entry in {@code config.xml}; Spring dependencies are
 * therefore resolved lazily through {@link Context#getRegisteredComponent}.
 */
public class EncounterIndexingAdvice implements AfterReturningAdvice {

	private static final Log log = LogFactory.getLog(EncounterIndexingAdvice.class);

	static final Set<String> TRIGGER_METHODS = new HashSet<>(
	        Arrays.asList("saveEncounter", "voidEncounter", "unvoidEncounter", "purgeEncounter"));

	@Override
	public void afterReturning(Object returnValue, Method method, Object[] args, Object target) {
		String name = method.getName();
		if (!TRIGGER_METHODS.contains(name)) {
			return;
		}

		Encounter encounter = encounterFrom(returnValue, args);
		if (encounter == null) {
			return;
		}

		try {
			dispatch(encounter, "purgeEncounter".equals(name));
		}
		catch (RuntimeException e) {
			// Best-effort per ADR Decision 12. Failures during serialization or dispatch must not
			// propagate back to the clinical-thread caller (the encounter save already succeeded).
			log.warn("EncounterIndexingAdvice failed for " + name + "; swallowing per ADR Decision 12", e);
		}
	}

	private void dispatch(Encounter encounter, boolean purge) {
		EncounterRecordSerializer serializer = serializer();
		BridgeIndexer indexer = indexer();
		AfterCommitDispatcher dispatcher = dispatcher();

		String resourceType = serializer.getResourceType();
		String uuid = encounter.getUuid();
		if (purge || encounter.getVoided()) {
			dispatcher.dispatch(() -> {
				try {
					indexer.delete(resourceType, uuid);
				}
				catch (RuntimeException e) {
					log.warn("Bridge skipping delete for encounter/" + uuid + " due to failure", e);
				}
			});
			return;
		}

		QueryDocument doc = serializer.serialize(encounter);
		if (doc == null) {
			return;
		}
		dispatcher.dispatch(() -> {
			try {
				indexer.index(doc);
			}
			catch (RuntimeException e) {
				log.warn("Bridge skipping index for encounter/" + doc.getResourceUuid()
				        + " due to failure", e);
			}
		});
	}

	private static Encounter encounterFrom(Object returnValue, Object[] args) {
		if (returnValue instanceof Encounter) {
			return (Encounter) returnValue;
		}
		if (args != null && args.length > 0 && args[0] instanceof Encounter) {
			return (Encounter) args[0];
		}
		return null;
	}

	EncounterRecordSerializer serializer() {
		return Context.getRegisteredComponent("querystore.serializer.encounter",
		        EncounterRecordSerializer.class);
	}

	BridgeIndexer indexer() {
		return Context.getRegisteredComponent("querystore.bridge.indexer", BridgeIndexer.class);
	}

	AfterCommitDispatcher dispatcher() {
		return Context.getRegisteredComponent("querystore.bridge.dispatcher", AfterCommitDispatcher.class);
	}
}
