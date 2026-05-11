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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.openmrs.module.querystore.api.impl.QueryStoreServiceImpl;
import org.openmrs.module.querystore.backend.lucene.LuceneBackendStore;
import org.openmrs.module.querystore.embedding.OnnxEmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lucene-tier retrieval-quality eval. Shared shape lives in
 * {@link AbstractRetrievalQualityEvalTest}; this class wires {@link LuceneBackendStore} under a
 * temp directory.
 *
 * <p>Run locally with:
 * <pre>
 * mvn -pl api -Dtest=RetrievalQualityEvalTest \
 *   -Dquerystore.eval.modelDir=/abs/path/to/model-and-vocab test
 * </pre>
 */
public class RetrievalQualityEvalTest extends AbstractRetrievalQualityEvalTest {

	private static final Logger log = LoggerFactory.getLogger(RetrievalQualityEvalTest.class);

	private LuceneBackendStore backend;

	private Path indexRoot;

	@Override
	protected long perCaseLatencyBudgetMs() {
		// In-JVM Lucene: 200ms is comfortable headroom over the observed 13-32ms.
		return 200L;
	}

	@BeforeAll
	public void setUpClass() throws Exception {
		if (!modelFilesExist()) {
			log.info("Skipping retrieval eval: set -Dquerystore.eval.modelDir to a directory "
			        + "containing a self-contained model.onnx and vocab.txt to run "
			        + "(current value: {})", MODEL_DIR);
			return;
		}
		indexRoot = Files.createTempDirectory("querystore-eval-");
		backend = new LuceneBackendStore(indexRoot);
		provider = new OnnxEmbeddingProvider(MODEL_PATH, VOCAB_PATH);
		QueryStoreServiceImpl impl = new QueryStoreServiceImpl();
		impl.setBackend(backend);
		impl.setEmbeddingProvider(provider);
		service = impl;

		evalDataset = EvalDataset.load("eval/retrieval-eval-dataset.json");

		List<String> records = loadDataset();
		long start = System.currentTimeMillis();
		// Per-doc indexing preserves the existing HNSW segment topology that the published recall
		// numbers were measured against. bulkUpsert would commit once with a single segment; that
		// changes graph-build order subtly and may shift kNN rank within top-30.
		for (int i = 0; i < records.size(); i++) {
			service.index(toDocument(i + 1, records.get(i)));
		}
		log.info("Indexed {} records in {} ms", records.size(), System.currentTimeMillis() - start);
	}

	@AfterAll
	public void tearDownClass() throws IOException {
		if (provider != null) {
			provider.close();
		}
		if (backend != null) {
			backend.close();
		}
		if (indexRoot != null) {
			deleteRecursively(indexRoot.toFile());
		}
	}

	private static void deleteRecursively(File f) {
		if (f.isDirectory()) {
			File[] kids = f.listFiles();
			if (kids != null) {
				for (File k : kids) {
					deleteRecursively(k);
				}
			}
		}
		// Best-effort: the JVM will GC any lingering Lucene file handles when the backend closes;
		// a leftover temp directory on a failed test isn't worth crashing for.
		f.delete();
	}
}
