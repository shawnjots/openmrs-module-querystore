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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.Encounter;
import org.openmrs.EncounterType;
import org.openmrs.Patient;
import org.openmrs.api.EncounterService;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.embedding.EmbeddingProvider;
import org.openmrs.module.querystore.model.QueryDocument;
import org.openmrs.module.querystore.serialization.EncounterRecordSerializer;

public class EncounterIndexingAdviceTest {

	private TestableAdvice advice;

	private EncounterRecordSerializer serializer;

	private BridgeIndexer indexer;

	private ImmediateDispatcher dispatcher;

	private RecordingService service;

	@Before
	public void setUp() {
		serializer = new EncounterRecordSerializer();
		service = new RecordingService();
		indexer = new BridgeIndexer(service, new ZeroEmbedder());
		dispatcher = new ImmediateDispatcher();
		advice = new TestableAdvice(serializer, indexer, dispatcher);
	}

	@Test
	public void afterReturning_saveEncounter_indexesProjectedDoc() throws Throwable {
		Encounter encounter = encounter("enc-1");

		advice.afterReturning(encounter, saveEncounter(), new Object[]{encounter}, null);

		assertEquals(1, dispatcher.count);
		assertEquals(1, service.indexed.size());
		assertEquals("enc-1", service.indexed.get(0).getResourceUuid());
		assertEquals("encounter", service.indexed.get(0).getResourceType());
	}

	@Test
	public void afterReturning_saveVoidedEncounter_emitsDeleteInsteadOfIndex() throws Throwable {
		// Per-node voided policy: a saveEncounter on a voided encounter routes to delete, mirroring
		// the saveObs(voidedObs) path in ObsIndexingAdvice.
		Encounter encounter = encounter("enc-v");
		encounter.setVoided(true);

		advice.afterReturning(encounter, saveEncounter(), new Object[]{encounter}, null);

		assertEquals("no index for voided encounter", 0, service.indexed.size());
		assertEquals(1, service.deleted.size());
		assertEquals("enc-v", service.deleted.get(0)[1]);
	}

	@Test
	public void afterReturning_voidEncounter_emitsDelete() throws Throwable {
		Encounter encounter = encounter("enc-2");
		encounter.setVoided(true);

		advice.afterReturning(encounter, voidEncounter(), new Object[]{encounter, "reason"}, null);

		assertEquals(1, service.deleted.size());
		assertEquals("encounter", service.deleted.get(0)[0]);
		assertEquals("enc-2", service.deleted.get(0)[1]);
	}

	@Test
	public void afterReturning_purgeEncounter_emitsDelete() throws Throwable {
		Encounter encounter = encounter("enc-3");
		// purge bypasses the voided flag — even a non-voided encounter must be deleted from the
		// read store when its row is removed from core.
		advice.afterReturning(null, purgeEncounter(), new Object[]{encounter}, null);

		assertEquals(1, service.deleted.size());
		assertEquals("enc-3", service.deleted.get(0)[1]);
	}

	@Test
	public void afterReturning_purgeEncounter_twoArgOverload_emitsDelete() throws Throwable {
		Encounter encounter = encounter("enc-3b");
		Method twoArg = EncounterService.class.getMethod("purgeEncounter", Encounter.class, boolean.class);
		advice.afterReturning(null, twoArg, new Object[]{encounter, Boolean.TRUE}, null);
		assertEquals(1, service.deleted.size());
		assertEquals("enc-3b", service.deleted.get(0)[1]);
	}

	@Test
	public void afterReturning_unvoidEncounter_emitsIndex() throws Throwable {
		Encounter encounter = encounter("enc-4");
		// unvoidEncounter has already cleared the voided flag by the time afterReturning fires.
		advice.afterReturning(encounter, unvoidEncounter(), new Object[]{encounter}, null);
		assertEquals(1, service.indexed.size());
		assertEquals("enc-4", service.indexed.get(0).getResourceUuid());
	}

	@Test
	public void afterReturning_unrelatedMethod_isIgnored() throws Throwable {
		Encounter encounter = encounter("enc-5");
		advice.afterReturning(encounter, EncounterService.class.getMethod("getEncounter", Integer.class),
		        new Object[]{1}, null);
		assertEquals(0, dispatcher.count);
	}

	@Test
	public void afterReturning_nullEncounter_isNoop() throws Throwable {
		advice.afterReturning(null, saveEncounter(), new Object[]{null}, null);
		assertEquals(0, dispatcher.count);
	}

	@Test
	public void afterReturning_serializerReturnsNull_noDispatch() throws Throwable {
		// AbstractRecordSerializer.serialize returns null when the populated document has empty
		// text. The bridge must not dispatch an index task for it. Bootstrap handles this the
		// same way (TypeBootstrapper.indexOne skips null docs).
		Encounter shellEncounter = new Encounter();
		shellEncounter.setUuid("empty");
		shellEncounter.setEncounterDatetime(new Date());

		advice.afterReturning(shellEncounter, saveEncounter(), new Object[]{shellEncounter}, null);

		assertEquals("no dispatch when serializer returns null", 0, dispatcher.count);
		assertEquals(0, service.indexed.size());
		assertEquals(0, service.deleted.size());
	}

	@Test
	public void afterReturning_returnValueMissingButArgsPresent_usesArgs() throws Throwable {
		// purgeEncounter returns void — the encounter must come from args[0]. Without this
		// fallback the advice would silently drop every purge call.
		Encounter encounter = encounter("enc-6");
		advice.afterReturning(null, purgeEncounter(), new Object[]{encounter}, null);
		assertEquals(1, service.deleted.size());
		assertEquals("enc-6", service.deleted.get(0)[1]);
	}

	@Test
	public void afterReturning_indexerThrows_isSwallowed() throws Throwable {
		// Per ADR Decision 12 the bridge is best-effort. The dispatched lambda's inner try/catch
		// prevents poison documents from propagating back to the clinical thread, so no document
		// is recorded in the read store.
		Encounter encounter = encounter("enc-7");
		BridgeIndexer poison = new BridgeIndexer(service, new ZeroEmbedder()) {
			@Override public void index(QueryDocument doc) {
				throw new RuntimeException("simulated poison");
			}
		};
		TestableAdvice resilient = new TestableAdvice(serializer, poison, dispatcher);

		resilient.afterReturning(encounter, saveEncounter(), new Object[]{encounter}, null);

		assertEquals("no index recorded when the indexer throws", 0, service.indexed.size());
		assertEquals(0, service.deleted.size());
	}

	@Test
	public void afterReturning_serializerThrows_isSwallowed() throws Throwable {
		// Mirrors ObsIndexingAdviceTest.afterReturning_serializerThrows_isSwallowed. The advice's
		// outer try/catch at EncounterIndexingAdvice.afterReturning catches synchronous-path
		// failures (serialization) so a poison source can't propagate back to the clinical
		// thread. Without this test the outer guard has no coverage.
		Encounter encounter = encounter("enc-8");
		TestableAdvice failing = new TestableAdvice(new EncounterRecordSerializer() {
			@Override protected void populate(Encounter record, QueryDocument doc) {
				throw new RuntimeException("simulated poison");
			}
		}, indexer, dispatcher);

		failing.afterReturning(encounter, saveEncounter(), new Object[]{encounter}, null);

		assertEquals("no index recorded when the serializer throws", 0, service.indexed.size());
		assertEquals(0, service.deleted.size());
	}

	// ---------- helpers ----------

	private static Method saveEncounter() throws NoSuchMethodException {
		return EncounterService.class.getMethod("saveEncounter", Encounter.class);
	}

	private static Method voidEncounter() throws NoSuchMethodException {
		return EncounterService.class.getMethod("voidEncounter", Encounter.class, String.class);
	}

	private static Method unvoidEncounter() throws NoSuchMethodException {
		return EncounterService.class.getMethod("unvoidEncounter", Encounter.class);
	}

	private static Method purgeEncounter() throws NoSuchMethodException {
		return EncounterService.class.getMethod("purgeEncounter", Encounter.class);
	}

	/**
	 * Builds an encounter that produces a non-empty serialized document — needs an EncounterType
	 * so {@code EncounterRecordSerializer.populate} writes anything. The serializer returns null
	 * for an encounter with no displayable fields.
	 */
	private static Encounter encounter(String uuid) {
		Encounter enc = new Encounter();
		enc.setUuid(uuid);
		enc.setEncounterDatetime(new Date());
		Patient patient = new Patient();
		patient.setUuid("patient-uuid");
		enc.setPatient(patient);
		EncounterType type = new EncounterType();
		type.setUuid("type-uuid");
		type.setName("Adult Outpatient Visit");
		enc.setEncounterType(type);
		return enc;
	}

	private static final class ImmediateDispatcher extends AfterCommitDispatcher {
		int count;

		ImmediateDispatcher() {
			super(new BridgeExecutor());
		}

		@Override
		public void dispatch(Runnable task) {
			count++;
			task.run();
		}
	}

	private static class TestableAdvice extends EncounterIndexingAdvice {
		private final EncounterRecordSerializer serializer;
		private final BridgeIndexer indexer;
		private final AfterCommitDispatcher dispatcher;

		TestableAdvice(EncounterRecordSerializer s, BridgeIndexer i, AfterCommitDispatcher d) {
			this.serializer = s;
			this.indexer = i;
			this.dispatcher = d;
		}

		@Override EncounterRecordSerializer serializer() { return serializer; }
		@Override BridgeIndexer indexer() { return indexer; }
		@Override AfterCommitDispatcher dispatcher() { return dispatcher; }
	}

	private static final class RecordingService implements QueryStoreService {
		final List<QueryDocument> indexed = new ArrayList<>();
		final List<String[]> deleted = new ArrayList<>();

		@Override public void index(QueryDocument document) { indexed.add(document); }
		@Override public void delete(String resourceType, String resourceUuid) {
			deleted.add(new String[]{resourceType, resourceUuid});
		}
		@Override public List<QueryDocument> searchByPatient(String p, String q, int l) {
			return java.util.Collections.emptyList();
		}
		@Override public List<QueryDocument> search(String q, int l) {
			return java.util.Collections.emptyList();
		}
		@Override public void onStartup() { }
		@Override public void onShutdown() { }
	}

	private static final class ZeroEmbedder implements EmbeddingProvider {
		@Override public int getDimensions() { return 8; }
		@Override public float[] embed(String text) { return new float[8]; }
	}
}
