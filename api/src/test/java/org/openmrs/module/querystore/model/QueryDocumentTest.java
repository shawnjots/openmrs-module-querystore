/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.model;

import static org.junit.Assert.assertEquals;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_OBS_GROUP_CONCEPT_NAME;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_SYNONYMS;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class QueryDocumentTest {

	@Test
	public void getEmbeddingInput_textOnly() {
		QueryDocument doc = new QueryDocument();
		doc.setText("Systolic blood pressure: 120 mmHg");
		assertEquals("Systolic blood pressure: 120 mmHg", doc.getEmbeddingInput());
	}

	@Test
	public void getEmbeddingInput_groupConceptPrefixed() {
		QueryDocument doc = new QueryDocument();
		doc.setText("Systolic blood pressure: 120 mmHg");
		doc.putMetadata(FIELD_OBS_GROUP_CONCEPT_NAME, "Vital signs");
		assertEquals("Vital signs — Systolic blood pressure: 120 mmHg", doc.getEmbeddingInput());
	}

	@Test
	public void getEmbeddingInput_synonymsAppended() {
		QueryDocument doc = new QueryDocument();
		doc.setText("Systolic blood pressure: 120 mmHg");
		doc.putMetadata(FIELD_SYNONYMS, Arrays.asList("SBP", "Systolic BP"));
		assertEquals("Systolic blood pressure: 120 mmHg SBP Systolic BP", doc.getEmbeddingInput());
	}

	@Test
	public void getEmbeddingInput_groupAndSynonymsCombined() {
		QueryDocument doc = new QueryDocument();
		doc.setText("Systolic blood pressure: 120 mmHg");
		doc.putMetadata(FIELD_OBS_GROUP_CONCEPT_NAME, "Vital signs");
		doc.putMetadata(FIELD_SYNONYMS, Collections.singletonList("SBP"));
		assertEquals("Vital signs — Systolic blood pressure: 120 mmHg SBP", doc.getEmbeddingInput());
	}

	@Test
	public void getEmbeddingInput_emptyGroupNameSkipped() {
		QueryDocument doc = new QueryDocument();
		doc.setText("Systolic blood pressure: 120 mmHg");
		doc.putMetadata(FIELD_OBS_GROUP_CONCEPT_NAME, "");
		assertEquals("Systolic blood pressure: 120 mmHg", doc.getEmbeddingInput());
	}

	@Test
	public void getEmbeddingInput_emptySynonymsListSkipped() {
		QueryDocument doc = new QueryDocument();
		doc.setText("Systolic blood pressure: 120 mmHg");
		doc.putMetadata(FIELD_SYNONYMS, Collections.emptyList());
		assertEquals("Systolic blood pressure: 120 mmHg", doc.getEmbeddingInput());
	}

	@Test
	public void getEmbeddingInput_groupPrefixDroppedWhenTextAbsent() {
		QueryDocument doc = new QueryDocument();
		doc.putMetadata(FIELD_OBS_GROUP_CONCEPT_NAME, "Vital signs");
		assertEquals("", doc.getEmbeddingInput());
	}

	@Test
	public void getEmbeddingInput_synonymsCarrySignalWithoutText() {
		QueryDocument doc = new QueryDocument();
		doc.putMetadata(FIELD_SYNONYMS, Arrays.asList("SBP", "Systolic BP"));
		assertEquals("SBP Systolic BP", doc.getEmbeddingInput());
	}

	@Test
	public void getEmbeddingInput_emptyAndNullSynonymsFiltered() {
		QueryDocument doc = new QueryDocument();
		doc.setText("Systolic blood pressure: 120 mmHg");
		doc.putMetadata(FIELD_SYNONYMS, Arrays.asList("SBP", "", null, "Systolic BP"));
		assertEquals("Systolic blood pressure: 120 mmHg SBP Systolic BP", doc.getEmbeddingInput());
	}
}
