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
import java.util.Collections;
import java.util.List;

import org.openmrs.module.querystore.QueryStoreConstants;

/**
 * Structured filter pushed down to the backend on every search call. Filter kinds are intentionally
 * limited; complex predicates compose by ANDing multiple filters in a {@link SearchRequest}.
 *
 * <p>{@link Kind#PATIENT_SCOPE} is privileged: backends must satisfy it sub-linearly per the
 * second invariant pinned in ADR Decision 3.
 */
public final class Filter {

	public enum Kind {
		TERM,
		IN,
		RANGE,
		PATIENT_SCOPE
	}

	private final Kind kind;

	private final String field;

	private final Object value;

	private final List<Object> values;

	private final Object from;

	private final Object to;

	private Filter(Kind kind, String field, Object value, List<Object> values, Object from, Object to) {
		this.kind = kind;
		this.field = field;
		this.value = value;
		this.values = values == null ? Collections.emptyList()
		        : Collections.unmodifiableList(new ArrayList<>(values));
		this.from = from;
		this.to = to;
	}

	public Kind getKind() {
		return kind;
	}

	public String getField() {
		return field;
	}

	public Object getValue() {
		return value;
	}

	public List<Object> getValues() {
		return values;
	}

	public Object getFrom() {
		return from;
	}

	public Object getTo() {
		return to;
	}

	public static Filter term(String field, Object value) {
		return new Filter(Kind.TERM, field, value, null, null, null);
	}

	public static Filter in(String field, List<Object> values) {
		return new Filter(Kind.IN, field, null, values, null, null);
	}

	public static Filter range(String field, Object from, Object to) {
		return new Filter(Kind.RANGE, field, null, null, from, to);
	}

	public static Filter patientScope(String patientUuid) {
		return new Filter(Kind.PATIENT_SCOPE, QueryStoreConstants.FIELD_PATIENT_UUID, patientUuid, null, null, null);
	}
}
