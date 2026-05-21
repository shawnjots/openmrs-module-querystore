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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.Allergy;
import org.openmrs.Patient;
import org.openmrs.api.PatientService;
import org.openmrs.module.querystore.bridge.BridgeAdviceTestSupport.ImmediateDispatcher;
import org.openmrs.module.querystore.bridge.BridgeAdviceTestSupport.RecordingService;
import org.openmrs.module.querystore.bridge.BridgeAdviceTestSupport.ZeroEmbedder;
import org.openmrs.module.querystore.model.QueryDocument;
import org.openmrs.module.querystore.serialization.PatientRecordSerializer;

public class PatientIndexingAdviceTest {

	private RecordingService service;

	private ImmediateDispatcher dispatcher;

	private TestableAdvice advice;

	@Before
	public void setUp() {
		service = new RecordingService();
		dispatcher = new ImmediateDispatcher();
		BridgeIndexer indexer = new BridgeIndexer(service, new ZeroEmbedder());
		advice = new TestableAdvice(new StubSerializer(), indexer, dispatcher);
	}

	@Test
	public void savePatient_indexes() throws Throwable {
		Patient p = patient("p-1", false);
		advice.afterReturning(p, PatientService.class.getMethod("savePatient", Patient.class),
		        new Object[]{p}, null);
		assertEquals(1, service.indexed.size());
	}

	@Test
	public void voidPatient_deletes() throws Throwable {
		Patient p = patient("p-2", true);
		advice.afterReturning(p,
		        PatientService.class.getMethod("voidPatient", Patient.class, String.class),
		        new Object[]{p, "reason"}, null);
		assertEquals(1, service.deleted.size());
	}

	@Test
	public void purgePatient_deletes() throws Throwable {
		Patient p = patient("p-3", false);
		// purgePatient returns void in the service interface; mirror that by passing null as
		// returnValue. entityFrom falls back to args[0] for void-returning advised methods.
		advice.afterReturning(null,
		        PatientService.class.getMethod("purgePatient", Patient.class),
		        new Object[]{p}, null);
		assertEquals(1, service.deleted.size());
	}

	@Test
	public void purgePatient_alsoBulkDeletesAcrossAllPerTypeStores() throws Throwable {
		// Core's purgePatient removes the patient from OpenMRS, so the read-store must follow.
		// The per-row delete on querystore_patient (covered by purgePatient_deletes above) only
		// removes the patient document itself; without the cross-type sweep, every obs,
		// encounter, condition, drug_order, etc. for this patient_uuid persists in the
		// querystore indefinitely — a PHI leak past the core deletion.
		Patient p = patient("p-purge", false);
		advice.afterReturning(null,
		        PatientService.class.getMethod("purgePatient", Patient.class),
		        new Object[]{p}, null);
		assertEquals("patient row still deleted", 1, service.deleted.size());
		assertEquals("cross-type bulkDeleteByPatient must fire on purgePatient",
		        1, service.bulkDeletedPatients.size());
		assertEquals("p-purge", service.bulkDeletedPatients.get(0));
	}

	@Test
	public void voidPatient_doesNotBulkDelete() throws Throwable {
		// Voiding is not a deletion event — the patient's chart must remain in the read-store so
		// it's still searchable for audit/recovery. Only purge triggers the cross-type sweep.
		Patient p = patient("p-void", true);
		advice.afterReturning(p,
		        PatientService.class.getMethod("voidPatient", Patient.class, String.class),
		        new Object[]{p, "reason"}, null);
		assertEquals(1, service.deleted.size());
		assertEquals("voidPatient must NOT bulk-delete cross-type docs",
		        0, service.bulkDeletedPatients.size());
	}

	@Test
	public void savePatient_doesNotBulkDelete() throws Throwable {
		// Save is the opposite of a deletion — indexes the patient document and must not touch
		// any other per-type store.
		Patient p = patient("p-save", false);
		advice.afterReturning(p, PatientService.class.getMethod("savePatient", Patient.class),
		        new Object[]{p}, null);
		assertEquals(1, service.indexed.size());
		assertEquals("savePatient must NOT bulk-delete cross-type docs",
		        0, service.bulkDeletedPatients.size());
	}

	@Test
	public void saveAllergy_notAdvised() throws Throwable {
		// "saveAllergy" is not in the patient advice's trigger-name set, so it's rejected by the
		// name filter before the type guard runs. (The allergy advice handles saveAllergy.)
		Allergy a = new Allergy();
		a.setUuid("allergy-x");
		advice.afterReturning(null,
		        PatientService.class.getMethod("saveAllergy", Allergy.class),
		        new Object[]{a}, null);
		assertEquals(0, dispatcher.count);
	}

	@Test
	public void purgePatient_dispatchesPerRowDeleteBeforeBulkDelete() throws Throwable {
		// Pins the ordering contract: the per-row delete on querystore_patient must happen BEFORE
		// the cross-type bulkDeleteByPatient sweep. Re-ordering to bulk-first would change the
		// partial-failure semantics (if bulk throws first, the per-row delete never runs and the
		// patient document survives in querystore_patient until the next bootstrap reconciles it).
		OrderedRecordingService ordered = new OrderedRecordingService();
		TestableAdvice orderedAdvice = newAdviceWithService(ordered);

		Patient p = patient("p-order", false);
		orderedAdvice.afterReturning(null,
		        PatientService.class.getMethod("purgePatient", Patient.class),
		        new Object[]{p}, null);

		int deleteIdx = ordered.callLog.indexOf("delete:patient:p-order");
		int bulkIdx = ordered.callLog.indexOf("bulkDelete:p-order");
		assertEquals("per-row delete must run", 0, deleteIdx);
		assertEquals("bulk-delete must run immediately after", 1, bulkIdx);
	}

	@Test
	public void purgePatient_perRowDeleteThrowing_stillFiresBulkDelete() throws Throwable {
		// Pins the per-row failure-isolation contract from ADR Decision 12. If a transient failure
		// against querystore_patient (locked index, DB hiccup) escaped the per-document try/catch
		// in AbstractIndexingAdvice.dispatch, the after-commit task would abort before reaching
		// the bulk-delete — leaking every cross-type document for the just-purged patient.
		OrderedRecordingService failing = new OrderedRecordingService();
		failing.throwOnDelete = new RuntimeException("simulated per-row delete failure");
		TestableAdvice failingAdvice = newAdviceWithService(failing);

		Patient p = patient("p-row-fail", false);
		// Must not throw — wrap() + per-doc catch absorb the failure.
		failingAdvice.afterReturning(null,
		        PatientService.class.getMethod("purgePatient", Patient.class),
		        new Object[]{p}, null);

		assertTrue("per-row delete was attempted",
		        failing.callLog.contains("delete:patient:p-row-fail"));
		assertTrue("bulk-delete must still fire after per-row failure",
		        failing.callLog.contains("bulkDelete:p-row-fail"));
	}

	@Test
	public void purgePatient_bulkDeleteThrowing_isSwallowed() throws Throwable {
		// Pins the bulk-delete-failure-swallow contract. A bulk-delete RuntimeException must not
		// escape the dispatched after-commit task — wrap()'s outer catch would log it anyway, but
		// the inner try/catch around indexer.bulkDeleteByPatient produces the per-patient warn
		// log operators rely on to distinguish a sweep failure from a missing-document failure.
		OrderedRecordingService failing = new OrderedRecordingService();
		failing.throwOnBulkDelete = new RuntimeException("simulated bulk-delete failure");
		TestableAdvice failingAdvice = newAdviceWithService(failing);

		Patient p = patient("p-bulk-fail", false);
		// Must not throw — both the inner per-step catch and the outer wrap() catch must absorb.
		failingAdvice.afterReturning(null,
		        PatientService.class.getMethod("purgePatient", Patient.class),
		        new Object[]{p}, null);

		assertTrue("per-row delete still happened before bulk-delete failure",
		        failing.callLog.contains("delete:patient:p-bulk-fail"));
		assertTrue("bulk-delete was attempted",
		        failing.callLog.contains("bulkDelete:p-bulk-fail"));
	}

	private TestableAdvice newAdviceWithService(OrderedRecordingService svc) {
		BridgeIndexer indexer = new BridgeIndexer(svc, new ZeroEmbedder());
		return new TestableAdvice(new StubSerializer(), indexer, dispatcher);
	}

	private static Patient patient(String uuid, boolean voided) {
		Patient p = new Patient();
		p.setUuid(uuid);
		p.setVoided(voided);
		return p;
	}

	private static class StubSerializer extends PatientRecordSerializer {
		@Override
		protected void populate(Patient record, QueryDocument doc) {
			doc.setText("stub-text");
		}
	}

	private static class TestableAdvice extends PatientIndexingAdvice {
		private final PatientRecordSerializer serializer;
		private final BridgeIndexer indexer;
		private final AfterCommitDispatcher dispatcher;

		TestableAdvice(PatientRecordSerializer s, BridgeIndexer i, AfterCommitDispatcher d) {
			this.serializer = s;
			this.indexer = i;
			this.dispatcher = d;
		}

		@Override protected PatientRecordSerializer serializer() { return serializer; }
		@Override BridgeIndexer indexer() { return indexer; }
		@Override AfterCommitDispatcher dispatcher() { return dispatcher; }
	}

	/**
	 * Records the relative ordering of {@code delete} and {@code bulkDeleteByPatient} calls so
	 * tests can pin the per-row-then-bulk contract, and supports per-method failure injection so
	 * the ADR Decision 12 per-step swallow guards can be exercised. Lives alongside the test that
	 * needs it rather than promoted to {@code BridgeAdviceTestSupport} — the failure-injection
	 * surface is specific to the patient cross-type sweep contract and would pollute the shared
	 * RecordingService that 10 other advice tests consume.
	 */
	private static final class OrderedRecordingService
	        implements org.openmrs.module.querystore.api.QueryStoreService {
		final java.util.List<String> callLog = new java.util.ArrayList<>();

		RuntimeException throwOnDelete;

		RuntimeException throwOnBulkDelete;

		@Override
		public org.openmrs.module.querystore.backend.WriteResult index(QueryDocument document) {
			// This fixture is only used by purge-path tests; the dispatch loop never enters the
			// index branch on those paths. A future regression that lands an index() call here is
			// a real surprise, not a silently-buffered event.
			throw new AssertionError("unexpected index() call on purge-only test fixture: "
			        + document.getResourceUuid());
		}

		@Override
		public void delete(String resourceType, String resourceUuid) {
			callLog.add("delete:" + resourceType + ":" + resourceUuid);
			if (throwOnDelete != null) {
				throw throwOnDelete;
			}
		}

		@Override
		public void bulkDeleteByPatient(String patientUuid) {
			callLog.add("bulkDelete:" + patientUuid);
			if (throwOnBulkDelete != null) {
				throw throwOnBulkDelete;
			}
		}

		@Override
		public java.util.List<QueryDocument> searchByPatient(String p, String q, int l) {
			return java.util.Collections.emptyList();
		}

		@Override
		public java.util.List<QueryDocument> search(String q, int l) {
			return java.util.Collections.emptyList();
		}

		@Override public void onStartup() { }

		@Override public void onShutdown() { }
	}
}
