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

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;
import org.openmrs.module.querystore.model.QueryDocument;

public class RankFusionTest {

	@Test
	public void rrf_fusesByRankAcrossBothSources() {
		// BM25:  A(rank 1), B(rank 2), C(rank 3)
		// kNN:   B(rank 1), D(rank 2), A(rank 3)
		// RRF (k=60): A = 1/61 + 1/63, B = 1/62 + 1/61, C = 1/63, D = 1/62
		// Expected fused order: B (0.01636 + 0.01613 = 0.03249) > A (0.01639 + 0.01587 = 0.03226) > D (0.01613) > C (0.01587)
		SearchResult bm25 = new SearchResult(Arrays.asList(
		    hit("A", 1), hit("B", 2), hit("C", 3)));
		SearchResult knn = new SearchResult(Arrays.asList(
		    hit("B", 1), hit("D", 2), hit("A", 3)));

		SearchResult fused = RankFusion.rrf(bm25, knn, 10);

		assertEquals(Arrays.asList("B", "A", "D", "C"), uuids(fused));
		assertEquals(1, fused.getHits().get(0).getRank());
		assertEquals(2, fused.getHits().get(1).getRank());
		assertEquals(3, fused.getHits().get(2).getRank());
		assertEquals(4, fused.getHits().get(3).getRank());
	}

	@Test
	public void rrf_respectsLimit() {
		SearchResult bm25 = new SearchResult(Arrays.asList(hit("A", 1), hit("B", 2), hit("C", 3)));
		SearchResult knn = new SearchResult(Collections.emptyList());

		SearchResult fused = RankFusion.rrf(bm25, knn, 2);

		assertEquals(Arrays.asList("A", "B"), uuids(fused));
	}

	@Test
	public void rrf_oneSideEmpty_preservesOtherSideOrdering() {
		SearchResult bm25 = new SearchResult(Arrays.asList(hit("A", 1), hit("B", 2)));
		SearchResult knn = new SearchResult(Collections.emptyList());

		SearchResult fused = RankFusion.rrf(bm25, knn, 10);

		assertEquals(Arrays.asList("A", "B"), uuids(fused));
	}

	@Test
	public void rrf_bothEmpty_returnsEmptyResult() {
		SearchResult fused = RankFusion.rrf(SearchResult.empty(), SearchResult.empty(), 10);
		assertEquals(0, fused.getHits().size());
	}

	private static Hit hit(String uuid, int rank) {
		QueryDocument doc = new QueryDocument();
		doc.setResourceUuid(uuid);
		return new Hit(doc, 0.0, rank);
	}

	private static List<String> uuids(SearchResult result) {
		return result.getHits().stream()
		    .map(h -> h.getDocument().getResourceUuid())
		    .collect(Collectors.toList());
	}
}
