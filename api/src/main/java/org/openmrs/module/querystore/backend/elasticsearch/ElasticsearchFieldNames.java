/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.backend.elasticsearch;

import org.openmrs.module.querystore.QueryStoreConstants;

/**
 * Elasticsearch field-name conventions for this backend. Top-level document fields reuse the
 * cross-cutting names from {@link QueryStoreConstants} so the SPI's {@code Filter.term("patient_uuid", ...)}
 * lands on the mapped field; {@link #METADATA_JSON} and {@link #META_PREFIX} are backend-specific
 * (the JSON blob is round-tripped to reconstruct the metadata map exactly the way Lucene/MySQL do,
 * and per-key companion fields under {@code meta.*} are populated by a dynamic_template so scalar
 * metadata is filterable without per-field mapping work).
 */
final class ElasticsearchFieldNames {

	private ElasticsearchFieldNames() {
	}

	static final String RESOURCE_UUID = QueryStoreConstants.FIELD_RESOURCE_UUID;

	static final String PATIENT_UUID = QueryStoreConstants.FIELD_PATIENT_UUID;

	static final String RECORD_DATE = QueryStoreConstants.FIELD_RECORD_DATE;

	static final String TEXT = "text";

	/**
	 * BM25-indexed companion of the {@code synonyms} metadata list per ADR Decision 6's
	 * Synonyms-and-group-obs convention. Stored as a top-level text field so the BM25 query can
	 * multi_match against {@code [text, synonyms]}; the structured list is also kept in
	 * {@link #METADATA_JSON} for rehydration. Duplication is intentional — the text field exists
	 * purely so BM25 has something to index.
	 */
	static final String SYNONYMS = QueryStoreConstants.FIELD_SYNONYMS;

	static final String EMBEDDING = "embedding";

	static final String METADATA_JSON = "metadata_json";

	static final String LAST_MODIFIED = "last_modified";

	/** Parent object holding per-key companion fields. {@link #META_PREFIX} is {@code META_PARENT + "."}. */
	static final String META_PARENT = "meta";

	/**
	 * Prefix for per-key indexed companions of the metadata JSON. {@code Filter.term("concept_uuid", "X")}
	 * lands on {@code meta.concept_uuid}, mapped to {@code keyword} by a dynamic_template. Scalar
	 * metadata only — collections fall through to the JSON blob and cannot be filtered in v1.
	 */
	static final String META_PREFIX = META_PARENT + ".";
}
