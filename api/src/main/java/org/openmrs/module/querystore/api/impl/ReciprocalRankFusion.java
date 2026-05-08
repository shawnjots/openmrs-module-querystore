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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openmrs.module.querystore.backend.Hit;
import org.openmrs.module.querystore.model.QueryDocument;

/**
 * Service-layer RRF: fuses two ranked result sets (BM25 + kNN) into a single ranked list using
 * {@code score = 1/(k + rank_bm25) + 1/(k + rank_knn)}, k=60. This runs uniformly for every
 * backend (Decision 3 SPI sub-point 2); ES is permitted to override with native RRF when
 * measurable benefit justifies it. Operates on rank only, so score-distribution differences
 * across backends do not leak into the fused result.
 */
final class ReciprocalRankFusion {

	private static final int K = 60;

	private ReciprocalRankFusion() {
	}

	static List<QueryDocument> fuse(List<Hit> bm25Hits, List<Hit> knnHits, int limit) {
		Map<String, Double> scores = new LinkedHashMap<>();
		Map<String, QueryDocument> docs = new LinkedHashMap<>();

		accumulate(bm25Hits, scores, docs);
		accumulate(knnHits, scores, docs);

		List<Map.Entry<String, Double>> entries = new ArrayList<>(scores.entrySet());
		entries.sort(Map.Entry.<String, Double>comparingByValue().reversed());

		int n = Math.min(entries.size(), limit);
		List<QueryDocument> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			out.add(docs.get(entries.get(i).getKey()));
		}
		return out;
	}

	private static void accumulate(List<Hit> hits, Map<String, Double> scores, Map<String, QueryDocument> docs) {
		for (Hit hit : hits) {
			String uuid = hit.getDocument().getResourceUuid();
			scores.merge(uuid, 1.0 / (K + hit.getRank()), Double::sum);
			docs.putIfAbsent(uuid, hit.getDocument());
		}
	}
}
