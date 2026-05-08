/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.backend.mysql;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.openmrs.module.querystore.QueryStoreConstants;
import org.openmrs.module.querystore.backend.Filter;

/**
 * Translates structured {@link Filter} predicates into a parameterised SQL fragment. Top-level
 * columns ({@code patient_uuid}, {@code resource_uuid}, {@code record_date}) become column
 * comparisons and use their B-tree indexes; everything else falls back to {@code JSON_EXTRACT} on
 * {@code metadata_json}, which is unindexed in v1 — fine for low-cardinality refinement filters,
 * not fine for primary access patterns.
 */
final class MysqlFilterTranslator {

	private static final Set<String> COLUMN_FIELDS = new HashSet<>(Arrays.asList(
	    QueryStoreConstants.FIELD_PATIENT_UUID,
	    QueryStoreConstants.FIELD_RESOURCE_UUID,
	    QueryStoreConstants.FIELD_RECORD_DATE));

	private static final Pattern FIELD_NAME_PATTERN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

	private final StringBuilder sql = new StringBuilder();

	private final List<Object> params = new ArrayList<>();

	String getSql() {
		return sql.toString();
	}

	List<Object> getParams() {
		return params;
	}

	void translate(List<Filter> filters) {
		boolean first = true;
		for (Filter f : filters) {
			if (!first) {
				sql.append(" AND ");
			}
			first = false;
			append(f);
		}
	}

	private void append(Filter f) {
		String columnRef = columnRef(f.getField());
		switch (f.getKind()) {
			case TERM:
			case PATIENT_SCOPE:
				sql.append(columnRef).append(" = ?");
				params.add(f.getValue());
				break;
			case IN:
				sql.append(columnRef).append(" IN (");
				List<Object> values = f.getValues();
				for (int i = 0; i < values.size(); i++) {
					if (i > 0) {
						sql.append(", ");
					}
					sql.append("?");
					params.add(values.get(i));
				}
				sql.append(")");
				break;
			case RANGE:
				sql.append("(");
				if (f.getFrom() != null) {
					sql.append(columnRef).append(" >= ?");
					params.add(f.getFrom());
				}
				if (f.getTo() != null) {
					if (f.getFrom() != null) {
						sql.append(" AND ");
					}
					sql.append(columnRef).append(" <= ?");
					params.add(f.getTo());
				}
				sql.append(")");
				break;
			default:
				throw new IllegalArgumentException("Unsupported filter kind: " + f.getKind());
		}
	}

	private static String columnRef(String field) {
		if (COLUMN_FIELDS.contains(field)) {
			return field;
		}
		return "JSON_UNQUOTE(JSON_EXTRACT(metadata_json, '$." + escapeJsonPath(field) + "'))";
	}

	private static String escapeJsonPath(String field) {
		// JSON path components must be alphanumeric/underscore; reject anything else to avoid SQL
		// injection through field names.
		if (!FIELD_NAME_PATTERN.matcher(field).matches()) {
			throw new IllegalArgumentException("Invalid metadata field name: " + field);
		}
		return field;
	}
}
