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

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Serializes a structured-metadata map to JSON for storage in the {@code metadata_json} column.
 * The MySQL backend uses JSON rather than per-type columns so that adding fields (or contributing
 * a new resource type via the module SPI per Decision 13) does not require runtime DDL.
 */
final class MysqlMetadataCodec {

	private static final ObjectMapper MAPPER = new ObjectMapper()
	        .registerModule(new JavaTimeModule())
	        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
	};

	private static final String EMPTY_OBJECT = "{}";

	private MysqlMetadataCodec() {
	}

	static String encode(Map<String, Object> metadata) {
		if (metadata == null || metadata.isEmpty()) {
			return EMPTY_OBJECT;
		}
		try {
			return MAPPER.writeValueAsString(metadata);
		}
		catch (IOException e) {
			throw new IllegalStateException("Could not serialize metadata to JSON", e);
		}
	}

	static Map<String, Object> decode(String json) {
		if (json == null || json.isEmpty() || EMPTY_OBJECT.equals(json)) {
			return Collections.emptyMap();
		}
		try {
			return MAPPER.readValue(json, MAP_TYPE);
		}
		catch (IOException e) {
			throw new IllegalStateException("Could not parse metadata JSON", e);
		}
	}
}
