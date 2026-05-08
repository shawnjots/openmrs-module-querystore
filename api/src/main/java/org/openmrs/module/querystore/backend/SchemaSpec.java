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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-resource-type schema descriptor passed to {@link BackendStore#ensureSchema(String, SchemaSpec)}.
 * The vector dimension is supplied by the active embedding provider; the field plan covers
 * type-specific structured fields. Cross-cutting fields (patient_uuid, resource_type,
 * resource_uuid, date, text, embedding) are handled implicitly by every backend and need not be
 * declared here.
 */
public final class SchemaSpec {

	private final int embeddingDimensions;

	private final Map<String, FieldType> fields;

	private SchemaSpec(int embeddingDimensions, Map<String, FieldType> fields) {
		this.embeddingDimensions = embeddingDimensions;
		this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
	}

	public int getEmbeddingDimensions() {
		return embeddingDimensions;
	}

	public Map<String, FieldType> getFields() {
		return fields;
	}

	public static Builder builder(int embeddingDimensions) {
		return new Builder(embeddingDimensions);
	}

	public static final class Builder {

		private final int embeddingDimensions;

		private final Map<String, FieldType> fields = new LinkedHashMap<>();

		private Builder(int embeddingDimensions) {
			this.embeddingDimensions = embeddingDimensions;
		}

		public Builder field(String name, FieldType type) {
			fields.put(name, type);
			return this;
		}

		public SchemaSpec build() {
			return new SchemaSpec(embeddingDimensions, fields);
		}
	}
}
