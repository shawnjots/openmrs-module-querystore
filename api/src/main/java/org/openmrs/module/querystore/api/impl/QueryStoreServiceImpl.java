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
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.backend.BackendStore;
import org.openmrs.module.querystore.backend.Filter;
import org.openmrs.module.querystore.backend.SearchRequest;
import org.openmrs.module.querystore.backend.SearchResult;
import org.openmrs.module.querystore.embedding.EmbeddingClient;
import org.openmrs.module.querystore.model.QueryDocument;

/**
 * Default {@link QueryStoreService}. Delegates writes to the configured {@link BackendStore} and
 * fuses BM25 + kNN ranks at the service layer using {@link ReciprocalRankFusion} (ADR Decision 3
 * SPI sub-point 2). The {@link EmbeddingClient} dependency is optional: when null, search degrades
 * to BM25-only.
 */
public class QueryStoreServiceImpl extends BaseOpenmrsService implements QueryStoreService {

	private static final Log log = LogFactory.getLog(QueryStoreServiceImpl.class);

	private BackendStore backend;

	private EmbeddingClient embeddingClient;

	public void setBackend(BackendStore backend) {
		this.backend = backend;
	}

	public void setEmbeddingClient(EmbeddingClient embeddingClient) {
		this.embeddingClient = embeddingClient;
	}

	@Override
	public void index(QueryDocument document) {
		if (document == null || document.getResourceUuid() == null) {
			return;
		}
		if (backend == null) {
			log.warn("No BackendStore wired; ignoring index call for " + document.getResourceUuid());
			return;
		}
		backend.upsert(document);
	}

	@Override
	public void delete(String resourceType, String resourceUuid) {
		if (resourceType == null || resourceUuid == null) {
			return;
		}
		if (backend == null) {
			return;
		}
		backend.delete(resourceType, resourceUuid);
	}

	@Override
	public List<QueryDocument> searchByPatient(String patientUuid, String query, int limit) {
		if (backend == null || patientUuid == null) {
			return Collections.emptyList();
		}
		return runHybrid(query, limit, Filter.patientScope(patientUuid));
	}

	@Override
	public List<QueryDocument> search(String query, int limit) {
		if (backend == null) {
			return Collections.emptyList();
		}
		return runHybrid(query, limit, null);
	}

	private List<QueryDocument> runHybrid(String query, int limit, Filter scope) {
		if (StringUtils.isBlank(query) || limit <= 0) {
			return Collections.emptyList();
		}
		SearchRequest.Builder bm25Req = SearchRequest.builder().queryText(query).limit(limit);
		if (scope != null) {
			bm25Req.filter(scope);
		}
		SearchResult bm25 = backend.bm25(bm25Req.build());

		if (embeddingClient == null) {
			return toDocuments(bm25);
		}
		float[] queryVector = embeddingClient.embed(query);
		SearchRequest.Builder knnReq = SearchRequest.builder().queryText(query).queryVector(queryVector).limit(limit);
		if (scope != null) {
			knnReq.filter(scope);
		}
		SearchResult knn = backend.knn(knnReq.build());

		return ReciprocalRankFusion.fuse(bm25.getHits(), knn.getHits(), limit);
	}

	private static List<QueryDocument> toDocuments(SearchResult result) {
		List<QueryDocument> out = new ArrayList<>(result.getHits().size());
		result.getHits().forEach(h -> out.add(h.getDocument()));
		return out;
	}
}
