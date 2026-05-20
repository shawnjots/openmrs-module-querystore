/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.backend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openmrs.module.querystore.model.QueryDocument;

/**
 * Reciprocal-rank fusion. Combines two separately-ranked result sets — typically BM25 and kNN —
 * into a single ranked {@link SearchResult} using {@code score = sum over sources of 1/(k + rank)}
 * with {@code k = 60}. Backed by {@link BackendStore#hybrid(SearchRequest)}'s default
 * implementation per ADR Decision 3; native overrides (e.g., Elasticsearch native RRF) are
 * permitted when measurable benefit justifies them.
 *
 * <p>Operates on {@link Hit#getRank()} only, so backend-specific score distributions do not leak
 * into the fused output. Output ranks are 1-based and monotone; output {@code rawScore} carries
 * the fused RRF score for telemetry but is not comparable across queries.
 */
public final class RankFusion {

	private static final int K = 60;

	private RankFusion() {
	}

	/**
	 * Fuses two ranked result sets into one.
	 *
	 * <p><b>Tie-break:</b> docs with equal fused score preserve insertion order from this method's
	 * accumulation pass — BM25 hits are inserted first, then kNN-only hits append. Java's stable
	 * sort on {@code Map.Entry::comparingByValue} keeps that order on equal scores, so ties favor
	 * BM25-leading. A future change from {@link java.util.LinkedHashMap} to an unordered map, or
	 * from {@code List.sort} to a non-stable sort, would silently shift tie ordering — this is the
	 * cross-backend rank-equality contract that callers depend on.
	 */
	public static SearchResult rrf(SearchResult bm25, SearchResult knn, int limit) {
		Map<String, Double> scores = new LinkedHashMap<>();
		Map<String, QueryDocument> docs = new LinkedHashMap<>();

		accumulate(bm25.getHits(), scores, docs);
		accumulate(knn.getHits(), scores, docs);

		List<Map.Entry<String, Double>> entries = new ArrayList<>(scores.entrySet());
		entries.sort(Map.Entry.<String, Double>comparingByValue().reversed());

		int n = Math.min(entries.size(), limit);
		List<Hit> fused = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			Map.Entry<String, Double> e = entries.get(i);
			fused.add(new Hit(docs.get(e.getKey()), e.getValue(), i + 1));
		}
		return new SearchResult(fused);
	}

	private static void accumulate(List<Hit> hits, Map<String, Double> scores, Map<String, QueryDocument> docs) {
		for (Hit hit : hits) {
			String uuid = hit.getDocument().getResourceUuid();
			scores.merge(uuid, 1.0 / (K + hit.getRank()), Double::sum);
			docs.putIfAbsent(uuid, hit.getDocument());
		}
	}
}
