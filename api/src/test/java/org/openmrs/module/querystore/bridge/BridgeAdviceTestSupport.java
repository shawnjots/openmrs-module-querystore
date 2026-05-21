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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.backend.WriteResult;
import org.openmrs.module.querystore.embedding.EmbeddingProvider;
import org.openmrs.module.querystore.model.QueryDocument;

/**
 * Shared fixtures for the bridge-advice unit tests. Every per-type advice test consumes the same
 * {@link RecordingService}, {@link ZeroEmbedder}, and {@link ImmediateDispatcher} shapes;
 * centralising them keeps each test class small and removes the clipboard-copy drift the prior
 * /harden cycle flagged.
 *
 * <p>Not promoted to a project-wide {@code testsupport} package because the existing bootstrap
 * tests have their own (subtly different) recording-service variants and unifying them is out of
 * this slice's scope.
 */
final class BridgeAdviceTestSupport {

	private BridgeAdviceTestSupport() {
	}

	static final class RecordingService implements QueryStoreService {
		final List<QueryDocument> indexed = new ArrayList<>();

		final List<String[]> deleted = new ArrayList<>();

		final List<String> bulkDeletedPatients = new ArrayList<>();

		@Override
		public WriteResult index(QueryDocument document) {
			indexed.add(document);
			return WriteResult.success();
		}

		@Override
		public void delete(String resourceType, String resourceUuid) {
			deleted.add(new String[]{resourceType, resourceUuid});
		}

		@Override
		public void bulkDeleteByPatient(String patientUuid) {
			bulkDeletedPatients.add(patientUuid);
		}

		@Override public List<QueryDocument> searchByPatient(String p, String q, int l) {
			return Collections.emptyList();
		}

		@Override public List<QueryDocument> search(String q, int l) { return Collections.emptyList(); }

		@Override public void onStartup() { }

		@Override public void onShutdown() { }
	}

	static final class ZeroEmbedder implements EmbeddingProvider {

		@Override public int getDimensions() { return 8; }

		@Override public float[] embed(String text) { return new float[8]; }
	}

	/**
	 * Synchronous dispatcher: runs the task immediately on the calling thread instead of registering
	 * an after-commit callback. Keeps test assertions synchronous and counts dispatches so the
	 * tests can pin "was the advice triggered?" without observing executor threads.
	 */
	static final class ImmediateDispatcher extends AfterCommitDispatcher {

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
}
