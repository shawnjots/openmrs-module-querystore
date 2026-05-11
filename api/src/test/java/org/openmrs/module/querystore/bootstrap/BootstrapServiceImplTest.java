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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.embedding.EmbeddingProvider;
import org.openmrs.module.querystore.model.QueryDocument;
import org.openmrs.module.querystore.serialization.ClinicalRecordSerializer;

public class BootstrapServiceImplTest {

	private BootstrapServiceImpl service;

	private InMemoryDao dao;

	@Before
	public void setUp() {
		service = new BootstrapServiceImpl();
		dao = new InMemoryDao();
		service.setProgressDao(dao);
		service.setQueryStoreService(new NullQueryStoreService());
		service.setEmbeddingProvider(new ZeroEmbedder());
	}

	@Test
	public void bootstrap_iteratesEveryRegisteredType() {
		EmptyPageBootstrapper obs = new EmptyPageBootstrapper("obs");
		EmptyPageBootstrapper enc = new EmptyPageBootstrapper("encounter");
		service.setBootstrappers(Arrays.asList(obs, enc));

		service.bootstrap();

		assertEquals(1, obs.fetchCount);
		assertEquals(1, enc.fetchCount);
		assertEquals(BootstrapStatus.COMPLETED, dao.find("obs").getStatus());
		assertEquals(BootstrapStatus.COMPLETED, dao.find("encounter").getStatus());
	}

	@Test
	public void bootstrap_typeSpecific_runsOnlyMatchingBootstrapper() {
		EmptyPageBootstrapper obs = new EmptyPageBootstrapper("obs");
		EmptyPageBootstrapper enc = new EmptyPageBootstrapper("encounter");
		service.setBootstrappers(Arrays.asList(obs, enc));

		service.bootstrap("encounter");

		assertEquals(0, obs.fetchCount);
		assertEquals(1, enc.fetchCount);
	}

	@Test
	public void bootstrap_unknownType_throws() {
		service.setBootstrappers(Collections.singletonList(new EmptyPageBootstrapper("obs")));
		try {
			service.bootstrap("nope");
			fail("expected IllegalArgumentException");
		}
		catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("nope"));
		}
	}

	@Test
	public void bootstrap_perTypeFailureDoesNotAbortRemaining() {
		EmptyPageBootstrapper failing = new EmptyPageBootstrapper("obs");
		failing.failure = new RuntimeException("boom");
		EmptyPageBootstrapper ok = new EmptyPageBootstrapper("encounter");
		service.setBootstrappers(Arrays.asList(failing, ok));

		service.bootstrap();

		assertEquals("failing bootstrapper was attempted", 1, failing.fetchCount);
		assertEquals("non-failing bootstrapper still ran after the failure", 1, ok.fetchCount);
		assertEquals(BootstrapStatus.FAILED, dao.find("obs").getStatus());
		assertEquals(BootstrapStatus.COMPLETED, dao.find("encounter").getStatus());
	}

	@Test
	public void bootstrap_resumesFromPersistedProgress() {
		BootstrapProgress prior = new BootstrapProgress("obs");
		prior.setCursorUuid("z");
		prior.setCursorDateChanged(Instant.parse("2025-03-15T09:00:00Z"));
		prior.setDocumentsIndexed(42);
		dao.store.put("obs", prior);
		EmptyPageBootstrapper obs = new EmptyPageBootstrapper("obs");
		service.setBootstrappers(Collections.singletonList(obs));

		service.bootstrap("obs");

		assertEquals("the persisted cursor uuid was passed into the first fetchPage call",
		        "z", obs.lastAfterUuid);
		assertEquals("the persisted cursor timestamp was passed into the first fetchPage call",
		        Instant.parse("2025-03-15T09:00:00Z"), obs.lastAfterDateChanged);
		assertEquals("documents_indexed preserved across resume",
		        42, dao.find("obs").getDocumentsIndexed());
	}

	@Test
	public void getStatus_byType_delegatesToDao() {
		BootstrapProgress p = new BootstrapProgress("obs");
		p.setDocumentsIndexed(7);
		dao.store.put("obs", p);

		BootstrapProgress out = service.getStatus("obs");
		assertEquals(7, out.getDocumentsIndexed());
	}

	@Test
	public void getStatus_all_delegatesToDao() {
		dao.store.put("obs", new BootstrapProgress("obs"));
		dao.store.put("encounter", new BootstrapProgress("encounter"));

		List<BootstrapProgress> all = service.getStatus();
		assertEquals(2, all.size());
	}

	@Test
	public void bootstrap_concurrentCallsForSameType_serialize() throws InterruptedException {
		// Two callers racing on bootstrap("test") must serialize on a per-type lock. Otherwise both
		// share the same progress row and trample each other's cursor/count writes. Documents are
		// version-protected by Slice A; bookkeeping needs this lock.
		CountDownLatch t1EnteredFetch = new CountDownLatch(1);
		CountDownLatch t1MayProceed = new CountDownLatch(1);
		AtomicInteger fetchCount = new AtomicInteger();

		BlockingBootstrapper b = new BlockingBootstrapper(t1EnteredFetch, t1MayProceed, fetchCount);
		service.setBootstrappers(Collections.singletonList(b));

		Thread t1 = new Thread(() -> service.bootstrap("test"));
		t1.start();
		assertTrue("first thread reached fetchPage", t1EnteredFetch.await(2, TimeUnit.SECONDS));

		Thread t2 = new Thread(() -> service.bootstrap("test"));
		t2.start();
		// Briefly: t2 has tried to acquire the lock; verify it's blocked, not running concurrently.
		Thread.sleep(150);
		assertEquals("second thread is blocked on the type lock; only one fetch in flight",
		        1, fetchCount.get());

		t1MayProceed.countDown();
		t1.join(2000);
		t2.join(2000);
		assertEquals("both callers completed; second ran sequentially after first released the lock",
		        2, fetchCount.get());
	}

	@Test
	public void registeredBootstrappersExposedReadOnly() {
		EmptyPageBootstrapper obs = new EmptyPageBootstrapper("obs");
		service.setBootstrappers(Collections.singletonList(obs));
		assertNotNull(service.getBootstrappers().get("obs"));
	}

	// ---------- fakes ----------

	/**
	 * Bootstrapper whose corpus is empty — fetchPage is called once, returns no records, the loop
	 * exits and the bootstrapper completes. Tracks the (cursor, uuid) it was invoked with so tests
	 * can pin the resume-from-progress semantics, and a failure can be injected to exercise the
	 * service's continue-on-error behavior.
	 */
	private static final class EmptyPageBootstrapper extends TypeBootstrapper<Object> {
		private final String type;
		int fetchCount;
		Instant lastAfterDateChanged;
		String lastAfterUuid;
		RuntimeException failure;

		EmptyPageBootstrapper(String type) { this.type = type; }

		@Override public String getResourceType() { return type; }
		@Override protected ClinicalRecordSerializer<Object> getSerializer() { return null; }
		@Override protected Instant getDateChanged(Object e) { return null; }
		@Override protected String getUuid(Object e) { return null; }

		@Override
		protected List<Object> fetchPage(Instant afterDateChanged, String afterUuid, int pageSize) {
			fetchCount++;
			lastAfterDateChanged = afterDateChanged;
			lastAfterUuid = afterUuid;
			if (failure != null) {
				throw failure;
			}
			return Collections.emptyList();
		}
	}

	/**
	 * Bootstrapper whose first invocation parks at a latch — lets the test verify that a second
	 * concurrent call waits for the lock rather than running in parallel.
	 */
	private static final class BlockingBootstrapper extends TypeBootstrapper<Object> {
		private final CountDownLatch entered;
		private final CountDownLatch mayProceed;
		private final AtomicInteger fetchCount;
		private boolean firstCall = true;

		BlockingBootstrapper(CountDownLatch entered, CountDownLatch mayProceed, AtomicInteger fetchCount) {
			this.entered = entered;
			this.mayProceed = mayProceed;
			this.fetchCount = fetchCount;
		}

		@Override public String getResourceType() { return "test"; }
		@Override protected ClinicalRecordSerializer<Object> getSerializer() { return null; }
		@Override protected Instant getDateChanged(Object e) { return null; }
		@Override protected String getUuid(Object e) { return null; }

		@Override
		protected List<Object> fetchPage(Instant afterDateChanged, String afterUuid, int pageSize) {
			fetchCount.incrementAndGet();
			if (firstCall) {
				firstCall = false;
				entered.countDown();
				try {
					mayProceed.await();
				}
				catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
				}
			}
			return Collections.emptyList();
		}
	}

	private static final class NullQueryStoreService implements QueryStoreService {
		@Override public void index(QueryDocument document) { }
		@Override public void delete(String resourceType, String resourceUuid) { }
		@Override public List<QueryDocument> searchByPatient(String p, String q, int l) { return Collections.emptyList(); }
		@Override public List<QueryDocument> search(String q, int l) { return Collections.emptyList(); }
		@Override public void onStartup() { }
		@Override public void onShutdown() { }
	}

	private static final class ZeroEmbedder implements EmbeddingProvider {
		@Override public int getDimensions() { return 8; }
		@Override public float[] embed(String text) { return new float[8]; }
	}

	private static final class InMemoryDao extends BootstrapProgressDao {
		final Map<String, BootstrapProgress> store = new HashMap<>();

		InMemoryDao() { super(null); }

		@Override public BootstrapProgress find(String resourceType) { return store.get(resourceType); }

		@Override
		public List<BootstrapProgress> findAll() {
			return new java.util.ArrayList<>(store.values());
		}

		@Override public void save(BootstrapProgress p) { store.put(p.getResourceType(), p); }
	}
}
