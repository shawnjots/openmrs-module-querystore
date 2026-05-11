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

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.openmrs.module.querystore.QueryStoreConstants;
import org.openmrs.module.querystore.backend.Filter;

/**
 * Translates structured {@link Filter} predicates into Lucene {@link Query} clauses. Top-level
 * fields ({@code patient_uuid}, {@code resource_uuid}, {@code record_date}) hit their dedicated
 * Lucene fields; metadata fields hit {@code meta.<key>} indexed at write time. Mirrors the MySQL
 * tier's filter surface (top-level columns + opaque metadata), trading the per-row JSON_EXTRACT
 * cost for an extra index write per scalar metadata key.
 */
final class LuceneFilterTranslator {

	private static final Set<String> COLUMN_FIELDS = new HashSet<>(Arrays.asList(
	    QueryStoreConstants.FIELD_PATIENT_UUID,
	    QueryStoreConstants.FIELD_RESOURCE_UUID,
	    QueryStoreConstants.FIELD_RECORD_DATE));

	private static final Pattern FIELD_NAME_PATTERN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

	private LuceneFilterTranslator() {
	}

	/**
	 * Builds the AND of all {@code filters}. Returns {@code null} when {@code filters} is empty so
	 * the caller can fall back to a bare BM25/kNN query instead of forcing a {@code MatchAllDocs}.
	 */
	static Query toQuery(List<Filter> filters) {
		if (filters == null || filters.isEmpty()) {
			return null;
		}
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
		for (Filter f : filters) {
			builder.add(translate(f), BooleanClause.Occur.FILTER);
		}
		return builder.build();
	}

	private static Query translate(Filter f) {
		switch (f.getKind()) {
			case TERM:
			case PATIENT_SCOPE:
				return scalarQuery(f.getField(), f.getValue());
			case IN: {
				BooleanQuery.Builder any = new BooleanQuery.Builder();
				for (Object v : f.getValues()) {
					any.add(scalarQuery(f.getField(), v), BooleanClause.Occur.SHOULD);
				}
				return any.build();
			}
			case RANGE:
				return rangeQuery(f.getField(), f.getFrom(), f.getTo());
			default:
				throw new IllegalArgumentException("Unsupported filter kind: " + f.getKind());
		}
	}

	private static Query scalarQuery(String field, Object value) {
		validateFieldName(field);
		// record_date is stored only as a LongPoint (StoredField is retrieval-only, not indexed),
		// so TERM/IN against it must use the point's exact-match query rather than the inverted
		// index. Matches the MySQL sibling, which uses the indexed DATE column for both = and
		// BETWEEN. Other top-level columns and metadata fall through to a string TermQuery on the
		// inverted index.
		if (QueryStoreConstants.FIELD_RECORD_DATE.equals(field)) {
			return LongPoint.newExactQuery(LuceneFieldNames.RECORD_DATE_POINT, toEpochDay(value));
		}
		String resolved = COLUMN_FIELDS.contains(field) ? field : LuceneFieldNames.META_PREFIX + field;
		return new TermQuery(new Term(resolved, String.valueOf(value)));
	}

	private static Query rangeQuery(String field, Object from, Object to) {
		validateFieldName(field);
		if (QueryStoreConstants.FIELD_RECORD_DATE.equals(field)) {
			long lower = from == null ? Long.MIN_VALUE : toEpochDay(from);
			long upper = to == null ? Long.MAX_VALUE : toEpochDay(to);
			return LongPoint.newRangeQuery(LuceneFieldNames.RECORD_DATE_POINT, lower, upper);
		}
		// Metadata RANGE filtering would require committing to a numeric encoding per metadata
		// field at write time. The MySQL tier supports this via JSON_EXTRACT, but in v1 the Lucene
		// tier draws the line at record_date — the only built-in numeric filter consumers need.
		throw new IllegalArgumentException(
		        "RANGE filter only supported on record_date in v1; got " + field);
	}

	private static long toEpochDay(Object value) {
		if (value instanceof LocalDate) {
			return ((LocalDate) value).toEpochDay();
		}
		throw new IllegalArgumentException(
		        "record_date RANGE filter expects LocalDate, got " + value.getClass().getName());
	}

	private static void validateFieldName(String field) {
		if (field == null || !FIELD_NAME_PATTERN.matcher(field).matches()) {
			throw new IllegalArgumentException("Invalid field name: " + field);
		}
	}
}
