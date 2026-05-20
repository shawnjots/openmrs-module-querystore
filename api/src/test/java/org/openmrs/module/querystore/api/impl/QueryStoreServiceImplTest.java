/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.api.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.backend.BackendCapabilities;
import org.openmrs.module.querystore.backend.BackendStore;
import org.openmrs.module.querystore.backend.Filter;
import org.openmrs.module.querystore.backend.BulkWriteResult;
import org.openmrs.module.querystore.backend.HealthStatus;
import org.openmrs.module.querystore.backend.SchemaSpec;
import org.openmrs.module.querystore.backend.SearchRequest;
import org.openmrs.module.querystore.backend.SearchResult;
import org.openmrs.module.querystore.backend.WriteResult;
import org.openmrs.module.querystore.bootstrap.BootstrapService;
import org.openmrs.module.querystore.bootstrap.BootstrapProgress;
import org.openmrs.module.querystore.model.QueryDocument;

public class QueryStoreServiceImplTest {

	private QueryStoreServiceImpl service;

	@Before
	public void setUp() {
		service = new QueryStoreServiceImpl();
	}

	@Test
	public void searchByPatient_returnsEmptyByDefault() {
		assertTrue(service.searchByPatient("patient-uuid", "glucose", 10).isEmpty());
	}

	@Test
	public void index_toleratesMinimalDocument() {
		QueryDocument doc = new QueryDocument();
		doc.setResourceType("obs");
		doc.setResourceUuid("obs-uuid");
		service.index(doc);
		assertNotNull(doc.getMetadata());
	}

	@Test
	public void delete_toleratesNullArguments() {
		service.delete(null, null);
		service.delete("obs", null);
		service.delete(null, "obs-uuid");
	}

	@Test
	public void searchByPatient_coldPatient_triggersEnsureIndexedOnce() {
		FakeBackendStore backend = new FakeBackendStore(false);
		RecordingBootstrapService bootstrap = new RecordingBootstrapService();
		service.setBackend(backend);
		service.setBootstrapServiceOverride(bootstrap);

		service.searchByPatient("patient-uuid", "glucose", 10);

		assertEquals(1, bootstrap.ensureIndexedCalls.size());
		assertEquals("patient-uuid", bootstrap.ensureIndexedCalls.get(0));
		assertEquals("backend was probed before triggering auto-index", 1, backend.existsByPatientCount.get());
	}

	@Test
	public void searchByPatient_hotPatient_delegatesToBackendHybridNotBm25KnnSeparately() {
		// Locks in the Decision 3 SPI reshape: the service calls backend.hybrid() (which fuses
		// internally via the interface-default RankFusion path or a native override) rather than
		// fusing bm25 + knn results in the service layer. A revert that fuses externally would
		// silently produce the same empty result on this stub but fail this assertion.
		FakeBackendStore backend = new FakeBackendStore(true);
		RecordingBootstrapService bootstrap = new RecordingBootstrapService();
		service.setBackend(backend);
		service.setBootstrapServiceOverride(bootstrap);

		service.searchByPatient("patient-uuid", "glucose", 10);

		assertEquals("service must delegate fusion to backend.hybrid()", 1, backend.hybridCount.get());
		assertEquals("service must not call bm25() directly", 0, backend.bm25Count.get());
		assertEquals("service must not call knn() directly", 0, backend.knnCount.get());
	}

	@Test
	public void searchByPatient_hotPatient_skipsEnsureIndexed() {
		FakeBackendStore backend = new FakeBackendStore(true);
		RecordingBootstrapService bootstrap = new RecordingBootstrapService();
		service.setBackend(backend);
		service.setBootstrapServiceOverride(bootstrap);

		service.searchByPatient("patient-uuid", "glucose", 10);

		assertTrue("ensureIndexed must not run when backend already has documents",
		        bootstrap.ensureIndexedCalls.isEmpty());
	}

	@Test
	public void searchByPatient_ensureIndexedFailure_doesNotBlockSearch() {
		// Per the ADR: index-failure must not block search. Whatever the backend already has is
		// still returned; empty results are no worse than pre-feature behavior.
		FakeBackendStore backend = new FakeBackendStore(false);
		RecordingBootstrapService bootstrap = new RecordingBootstrapService();
		bootstrap.onEnsureIndexed = uuid -> { throw new RuntimeException("boom"); };
		service.setBackend(backend);
		service.setBootstrapServiceOverride(bootstrap);

		// Does not throw; returns whatever the (empty) backend has.
		List<QueryDocument> result = service.searchByPatient("patient-uuid", "glucose", 10);
		assertTrue(result.isEmpty());
	}

	@Test
	public void searchByPatient_bootstrapServiceUnavailable_skipsAutoIndexQuietly() {
		// Production wiring resolves BootstrapService via Context.getService; tests run without an
		// OpenMRS context. With no override set, Context.getService throws (no service context /
		// service not registered), and the auto-index step is skipped quietly via the catch — the
		// search still runs and returns whatever the backend has.
		FakeBackendStore backend = new FakeBackendStore(false);
		service.setBackend(backend);

		List<QueryDocument> result = service.searchByPatient("patient-uuid", "glucose", 10);
		assertTrue(result.isEmpty());
	}

	// ---------- fakes ----------

	private static final class FakeBackendStore implements BackendStore {
		private final boolean existsByPatientReturn;
		final AtomicInteger existsByPatientCount = new AtomicInteger();
		final AtomicInteger hybridCount = new AtomicInteger();
		final AtomicInteger bm25Count = new AtomicInteger();
		final AtomicInteger knnCount = new AtomicInteger();

		FakeBackendStore(boolean existsByPatientReturn) {
			this.existsByPatientReturn = existsByPatientReturn;
		}

		@Override public boolean existsByPatient(String patientUuid) {
			existsByPatientCount.incrementAndGet();
			return existsByPatientReturn;
		}

		@Override public void ensureSchema(String resourceType, SchemaSpec spec) { }
		@Override public void deleteSchema(String resourceType) { }
		@Override public WriteResult upsert(QueryDocument doc) { return null; }
		@Override public WriteResult delete(String resourceType, String resourceUuid) { return null; }
		@Override public BulkWriteResult bulkUpsert(List<QueryDocument> docs) { return null; }
		@Override public BulkWriteResult bulkDelete(String resourceType, List<String> uuids) { return null; }
		@Override public BulkWriteResult bulkDeleteByPatient(String patientUuid) { return null; }
		@Override public SearchResult bm25(SearchRequest req) { bm25Count.incrementAndGet(); return SearchResult.empty(); }
		@Override public SearchResult knn(SearchRequest req) { knnCount.incrementAndGet(); return SearchResult.empty(); }
		@Override public SearchResult hybrid(SearchRequest req) { hybridCount.incrementAndGet(); return SearchResult.empty(); }
		// Non-null capabilities even though this fake currently overrides hybrid(): the
		// BackendStore.hybrid default-method (post-Decision-3 SPI reshape) dereferences
		// capabilities().supportsKnn(), so a future test that forgets to override hybrid()
		// — or inherits this fake — would NPE on the default path if capabilities() returned
		// null. Returning a real object here keeps the fake safe under that drift.
		@Override public BackendCapabilities capabilities() {
			return new BackendCapabilities(true, false, false, 1_000_000, EnumSet.allOf(Filter.Kind.class));
		}
		@Override public HealthStatus health() { return null; }
	}

	private static final class RecordingBootstrapService implements BootstrapService {
		final java.util.List<String> ensureIndexedCalls = new java.util.ArrayList<>();

		java.util.function.Consumer<String> onEnsureIndexed;

		@Override public void bootstrap() { }
		@Override public void bootstrap(String resourceType) { }
		@Override public void ensureIndexed(String patientUuid) {
			ensureIndexedCalls.add(patientUuid);
			if (onEnsureIndexed != null) {
				onEnsureIndexed.accept(patientUuid);
			}
		}
		@Override public List<BootstrapProgress> getStatus() { return Collections.emptyList(); }
		@Override public BootstrapProgress getStatus(String resourceType) { return null; }
		@Override public void onStartup() { }
		@Override public void onShutdown() { }
	}
}
