/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.eval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.openmrs.module.querystore.api.impl.QueryStoreServiceImpl;
import org.openmrs.module.querystore.backend.SchemaSpec;
import org.openmrs.module.querystore.backend.elasticsearch.ElasticsearchBackendStore;
import org.openmrs.module.querystore.backend.elasticsearch.ElasticsearchClientFactory;
import org.openmrs.module.querystore.embedding.OnnxEmbeddingProvider;
import org.openmrs.module.querystore.model.QueryDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Elasticsearch-tier retrieval-quality eval against a Testcontainers ES 8.13 cluster. Shared shape
 * lives in {@link AbstractRetrievalQualityEvalTest}; this class boots the container, wires the
 * backend, ensures dense_vector mappings up front (ES requires fixed dims at index-create time),
 * and uses bulk indexing to keep the {@code refresh=wait_for} cost paid once rather than per doc.
 *
 * <p>Run locally with:
 * <pre>
 * mvn -pl api -Pintegration -Dtest=ElasticsearchRetrievalQualityEvalIntegrationTest \
 *   -Dquerystore.eval.modelDir=/abs/path/to/model-and-vocab test
 * </pre>
 */
public class ElasticsearchRetrievalQualityEvalIntegrationTest extends AbstractRetrievalQualityEvalTest {

	private static final Logger log = LoggerFactory.getLogger(ElasticsearchRetrievalQualityEvalIntegrationTest.class);

	private static final String IMAGE = "docker.elastic.co/elasticsearch/elasticsearch:8.13.4";

	private ElasticsearchContainer es;

	private ElasticsearchClientFactory clientFactory;

	private ElasticsearchBackendStore backend;

	@Override
	protected long perCaseLatencyBudgetMs() {
		// Network round-trip to the testcontainer; observed 25-61ms. 2000ms catches pathological
		// regressions without flaking on first-call cold caches.
		return 2000L;
	}

	@BeforeAll
	public void setUpClass() throws Exception {
		if (!modelFilesExist()) {
			log.info("Skipping retrieval eval: set -Dquerystore.eval.modelDir to a directory "
			        + "containing a self-contained model.onnx and vocab.txt to run "
			        + "(current value: {})", MODEL_DIR);
			return;
		}
		es = new ElasticsearchContainer(DockerImageName.parse(IMAGE))
		        .withEnv("xpack.security.enabled", "false")
		        .withEnv("discovery.type", "single-node")
		        .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");
		es.start();

		clientFactory = new ElasticsearchClientFactory("http://" + es.getHttpHostAddress());
		backend = new ElasticsearchBackendStore(clientFactory);
		provider = new OnnxEmbeddingProvider(MODEL_PATH, VOCAB_PATH);

		// ES dense_vector mapping must be fixed at index-create time, unlike Lucene/MySQL which
		// auto-create on upsert. Pin dims from a sample embedding so a model swap surfaces here
		// with a clear mapping mismatch rather than as opaque downstream failures.
		int dims = provider.embed("dimension probe").length;
		SchemaSpec spec = SchemaSpec.builder(dims).build();
		for (String type : Arrays.asList("obs", "diagnosis", "condition", "drug_order", "allergy", "program")) {
			backend.ensureSchema(type, spec);
		}

		QueryStoreServiceImpl impl = new QueryStoreServiceImpl();
		impl.setBackend(backend);
		impl.setEmbeddingProvider(provider);
		service = impl;

		evalDataset = EvalDataset.load("eval/retrieval-eval-dataset.json");

		List<String> records = loadDataset();
		long start = System.currentTimeMillis();
		// Single bulk request pays refresh=wait_for once instead of 153× — drops indexing from
		// ~150s to a few seconds while still honouring the SPI's durable+visible contract.
		List<QueryDocument> docs = new ArrayList<>(records.size());
		for (int i = 0; i < records.size(); i++) {
			docs.add(toDocument(i + 1, records.get(i)));
		}
		backend.bulkUpsert(docs);
		log.info("Indexed {} records in {} ms", records.size(), System.currentTimeMillis() - start);
	}

	@AfterAll
	public void tearDownClass() {
		if (provider != null) {
			provider.close();
		}
		if (backend != null) {
			backend.close();
		}
		if (es != null) {
			es.stop();
		}
	}
}
