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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_OBS_GROUP_CONCEPT_NAME;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_SYNONYMS;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.embedding.EmbeddingProvider;
import org.openmrs.module.querystore.model.QueryDocument;

public class BridgeIndexerTest {

	private BridgeIndexer indexer;

	private RecordingService service;

	private CountingEmbedder embedder;

	@Before
	public void setUp() {
		service = new RecordingService();
		embedder = new CountingEmbedder();
		indexer = new BridgeIndexer(service, embedder);
	}

	@Test
	public void index_embedsAndForwardsToService() {
		QueryDocument doc = new QueryDocument();
		doc.setResourceType("obs");
		doc.setResourceUuid("u-1");
		doc.setText("Fasting blood glucose: 11.2 mmol/L");

		indexer.index(doc);

		assertEquals(1, service.indexed.size());
		QueryDocument indexed = service.indexed.get(0);
		assertSame(doc, indexed);
		assertNotNull("embedding populated", indexed.getEmbedding());
		assertEquals(8, indexed.getEmbedding().length);
		assertEquals(1, embedder.inputs.size());
		assertEquals("Fasting blood glucose: 11.2 mmol/L", embedder.inputs.get(0));
	}

	@Test
	public void index_embeddingInputIncludesGroupNameAndSynonyms() {
		QueryDocument doc = new QueryDocument();
		doc.setResourceType("obs");
		doc.setResourceUuid("u-2");
		doc.setText("Systolic blood pressure: 120 mmHg");
		doc.putMetadata(FIELD_OBS_GROUP_CONCEPT_NAME, "Vital signs");
		doc.putMetadata(FIELD_SYNONYMS, Arrays.asList("SBP", "Systolic BP"));

		indexer.index(doc);

		assertEquals(1, embedder.inputs.size());
		assertEquals(
		    "Vital signs — Systolic blood pressure: 120 mmHg SBP Systolic BP",
		    embedder.inputs.get(0));
	}

	@Test
	public void delete_forwardsToService() {
		indexer.delete("obs", "u-3");
		assertEquals(1, service.deleted.size());
		assertEquals("obs", service.deleted.get(0)[0]);
		assertEquals("u-3", service.deleted.get(0)[1]);
	}

	private static final class RecordingService implements QueryStoreService {
		final List<QueryDocument> indexed = new ArrayList<>();
		final List<String[]> deleted = new ArrayList<>();

		@Override public void index(QueryDocument document) { indexed.add(document); }
		@Override public void delete(String resourceType, String resourceUuid) {
			deleted.add(new String[]{resourceType, resourceUuid});
		}
		@Override public List<QueryDocument> searchByPatient(String p, String q, int l) {
			return Collections.emptyList();
		}
		@Override public List<QueryDocument> search(String q, int l) { return Collections.emptyList(); }
		@Override public void onStartup() { }
		@Override public void onShutdown() { }
	}

	private static final class CountingEmbedder implements EmbeddingProvider {
		final List<String> inputs = new ArrayList<>();

		@Override public int getDimensions() { return 8; }

		@Override
		public float[] embed(String text) {
			inputs.add(text);
			return new float[8];
		}
	}
}
