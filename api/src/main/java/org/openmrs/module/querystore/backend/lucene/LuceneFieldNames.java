/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.backend.lucene;

import org.openmrs.module.querystore.QueryStoreConstants;

/**
 * Lucene field-name conventions for this backend. Top-level document fields reuse the
 * cross-cutting names from {@link QueryStoreConstants} so the SPI's {@code Filter.term("patient_uuid", ...)}
 * lands on the indexed field; secondary fields ({@code *_POINT}, {@code *_STORED},
 * {@code META_PREFIX}, embedding) are Lucene-specific.
 */
final class LuceneFieldNames {

	private LuceneFieldNames() {
	}

	static final String RESOURCE_UUID = QueryStoreConstants.FIELD_RESOURCE_UUID;

	static final String PATIENT_UUID = QueryStoreConstants.FIELD_PATIENT_UUID;

	static final String RECORD_DATE = QueryStoreConstants.FIELD_RECORD_DATE;

	/** Indexed numeric companion of {@link #RECORD_DATE} carrying days-since-epoch for range queries. */
	static final String RECORD_DATE_POINT = RECORD_DATE + "_point";

	static final String TEXT = "text";

	/**
	 * BM25-indexed companion of the {@code synonyms} metadata list per ADR Decision 6's
	 * Synonyms-and-group-obs convention. Stored as a single space-joined TextField alongside
	 * {@link #TEXT}; the query parser searches both fields so an "HTN" query hits a doc whose
	 * preferred name is "Hypertension". The structured list also lives in {@link #METADATA_JSON}
	 * for rehydration; the duplication is intentional — the text field exists purely so BM25 has
	 * something to index.
	 */
	static final String SYNONYMS = QueryStoreConstants.FIELD_SYNONYMS;

	/**
	 * BM25-indexed companion of the {@code description} metadata string. The concept's free-text
	 * description (CIEL-authored) gives the query parser additional vocabulary so a record whose
	 * preferred name doesn't carry the category word still surfaces — e.g. "Blood urea nitrogen"
	 * doesn't say "kidney" in its name but its description does. Stored {@code Field.Store.NO}
	 * because consumers (chartsearchai's chart, the LLM) should read the citation-clean
	 * {@link #TEXT}; the description exists purely for retrieval vocabulary.
	 */
	static final String DESCRIPTION = QueryStoreConstants.FIELD_DESCRIPTION;

	/**
	 * Stored byte blob carrying the raw float32 embedding. Doubles as the source the brute-force
	 * kNN scan iterates over — Lucene 8 ships no native HNSW kNN field, and the tier is pinned
	 * to 8.11.2 to match core's transitive Lucene (see {@code LuceneBackendStore} class javadoc).
	 */
	static final String EMBEDDING_STORED = "embedding_bytes";

	static final String METADATA_JSON = "metadata_json";

	static final String LAST_MODIFIED = "last_modified";

	/**
	 * Prefix for per-key indexed companions of the metadata JSON. {@code Filter.term("concept_uuid", "X")}
	 * lands on {@code meta.concept_uuid} so the term predicate uses the inverted index instead of
	 * scanning the JSON blob. Scalar metadata only — collections fall through to the JSON blob and
	 * cannot be filtered in v1.
	 */
	static final String META_PREFIX = "meta.";
}
