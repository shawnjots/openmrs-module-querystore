/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.events;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.BaseOpenmrsData;
import org.openmrs.Person;
import org.openmrs.aop.event.PurgeServiceEvent;
import org.openmrs.aop.event.RetireServiceEvent;
import org.openmrs.aop.event.SaveServiceEvent;
import org.openmrs.aop.event.UnretireServiceEvent;
import org.openmrs.aop.event.UnvoidServiceEvent;
import org.openmrs.aop.event.VoidServiceEvent;
import org.openmrs.api.context.Context;
import org.openmrs.person.PersonMergeLog;
import org.openmrs.module.querystore.bootstrap.BootstrapService;
import org.openmrs.module.querystore.sync.AfterCommitDispatcher;
import org.openmrs.module.querystore.sync.RecordIndexer;
import org.openmrs.module.querystore.sync.RecordProjector;
import org.openmrs.module.querystore.serialization.ClinicalRecordSerializer;
import org.springframework.context.event.EventListener;

/**
 * Events-first sync consumer (ADR Decision 12): subscribes to core's #6084 {@code *ServiceEvent}s
 * and projects each saved/voided/purged entity into the read store through {@link RecordProjector}.
 * This is the sole sync path — the AOP migration bridge was removed once events was verified
 * end-to-end.
 *
 * <p><b>Synchronous {@code @EventListener}, by design.</b> The core advice publishes the event
 * inside the originating transaction, so these handlers run on the clinical thread with the session
 * open — exactly where serialization must happen for lazy navigations to resolve. The expensive
 * embed + write is deferred to after commit by {@link AfterCommitDispatcher} inside the projector,
 * so the clinical thread is not blocked on embedding and nothing is indexed for a rolled-back
 * transaction. (Verified end-to-end against a real 2.9 server by {@code CoreServiceEventTest}.)
 *
 * <p><b>Disposition.</b> Every event but purge maps to {@code project(entity, purge=false)} — except
 * the {@code PersonMergeLog} save, which routes to merge reconciliation (see Patient merge below).
 * For a projected event the entity's own {@code voided} flag — set before the event publishes —
 * drives index-vs-delete (ADR Decision 10). {@code PurgeServiceEvent} maps to {@code purge=true}
 * (delete + patient sweep). {@code Retire}/{@code Unretire} are handled for completeness but are inert
 * for the current indexed types, which are all {@code OpenmrsData}.
 *
 * <p><b>Patient merge.</b> Core emits no merge event, but {@code mergePatients} ends by saving a
 * {@link PersonMergeLog}, so {@code onSave} also receives {@code SaveServiceEvent<PersonMergeLog>}
 * and routes it to {@link #reconcileMerge} instead of normal projection — see that method for the
 * delete-loser + reindex-winner contract.
 *
 * <p><b>Footprint.</b> Core's #6084 advice is global, so these handlers fire for <em>every</em>
 * {@code OpenmrsService} save/void/purge, not only indexed types; a non-indexed entity is dropped
 * cheaply by the {@code instanceof BaseOpenmrsData} check and the serializer lookup returning null.
 */
public class CoreServiceEventListener {

	private final Log log = LogFactory.getLog(getClass());

	@EventListener
	public void onSave(SaveServiceEvent<?> event) {
		Object entity = event.getEntity();
		if (entity instanceof PersonMergeLog) {
			reconcileMerge((PersonMergeLog) entity);
			return;
		}
		project(entity, false);
	}

	@EventListener
	public void onVoid(VoidServiceEvent<?> event) {
		project(event.getEntity(), false);
	}

	@EventListener
	public void onUnvoid(UnvoidServiceEvent<?> event) {
		project(event.getEntity(), false);
	}

	@EventListener
	public void onRetire(RetireServiceEvent<?> event) {
		project(event.getEntity(), false);
	}

	@EventListener
	public void onUnretire(UnretireServiceEvent<?> event) {
		project(event.getEntity(), false);
	}

	@EventListener
	public void onPurge(PurgeServiceEvent<?> event) {
		project(event.getEntity(), true);
	}

	void project(Object entity, boolean purge) {
		// Indexed types are all OpenmrsData; metadata (retire/unretire) is not projected here, and a
		// non-data entity has no RecordProjector path (it needs getVoided()).
		if (!(entity instanceof BaseOpenmrsData)) {
			return;
		}
		BaseOpenmrsData data = (BaseOpenmrsData) entity;
		ClinicalRecordSerializer<BaseOpenmrsData> serializer = registry().resolve(data);
		if (serializer == null) {
			return;
		}
		try {
			RecordProjector.project(serializer, data, purge, indexer(), dispatcher());
		}
		catch (RuntimeException e) {
			// Best-effort per ADR Decision 12: a projection failure must not
			// propagate back into the clinical transaction that published the event.
			log.warn("Events consumer failed projecting " + data.getClass().getSimpleName()
			        + "; swallowing per ADR Decision 12", e);
		}
	}

	/**
	 * Patient-merge reconciliation (ADR Decision 12 / "Patient merge handling"). Core has no merge
	 * event; {@code PatientService.mergePatients} ends by saving a {@link PersonMergeLog} through the
	 * service proxy, which #6084 surfaces as {@code SaveServiceEvent<PersonMergeLog>} carrying the
	 * winner (surviving) and loser (merged-away) {@link Person}s. By the time it fires, core has
	 * committed every reassignment — the loser's clinical data now belongs to the winner — and voided
	 * the loser.
	 *
	 * <p>We reconcile the read store authoritatively rather than relying on the per-record save events
	 * the merge incidentally fires: those cover the types core re-saves through a proxy (encounter,
	 * visit, non-encounter obs, patient program) but not orders/conditions/allergies/diagnoses or
	 * encounter-contained obs, which would otherwise linger under the loser uuid. So we sweep the
	 * loser's documents and re-project the winner's now-complete chart via {@code reindexPatient}
	 * (delete + unconditional re-projection under the per-patient lock) — idempotent and independent
	 * of which reassignments happened to emit events. Person and Patient share a uuid, so the person
	 * uuid is the document {@code patient_uuid}.
	 *
	 * <p>The whole reconcile is deferred after-commit on the sync executor's daemon thread (like every
	 * projection) so the heavy re-projection neither runs against uncommitted merge state nor blocks
	 * the clinical thread that performed the merge.
	 */
	void reconcileMerge(PersonMergeLog mergeLog) {
		Person winner = mergeLog.getWinner();
		Person loser = mergeLog.getLoser();
		if (winner == null || loser == null) {
			// A merge log missing either end is malformed; there is nothing to reconcile.
			return;
		}
		String winnerUuid = winner.getUuid();
		String loserUuid = loser.getUuid();
		dispatcher().dispatch(() -> {
			indexer().bulkDeleteByPatient(loserUuid);
			bootstrapService().reindexPatient(winnerUuid);
		});
	}

	SerializerRegistry registry() {
		return Context.getRegisteredComponent("querystore.serializerRegistry", SerializerRegistry.class);
	}

	BootstrapService bootstrapService() {
		return Context.getService(BootstrapService.class);
	}

	RecordIndexer indexer() {
		return Context.getRegisteredComponent("querystore.sync.indexer", RecordIndexer.class);
	}

	AfterCommitDispatcher dispatcher() {
		return Context.getRegisteredComponent("querystore.sync.dispatcher", AfterCommitDispatcher.class);
	}
}
