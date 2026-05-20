/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

/**
 * Locks in the {@link AbstractRetrievalQualityEvalTest#synonymsForText} lookup contract so a
 * refactor that silently drops or rewires a key (e.g., reverts the post-Decision-6 synonyms
 * augmentation) fails here in CI rather than only when the eval is re-run manually with
 * {@code -Dquerystore.eval.modelDir}. The eval recall threshold is too loose (0.40) to catch a
 * single-case regression to 0.000 against the ~0.91 baseline.
 */
public class EvalSynonymsLookupTest {

	@Test
	public void respiratoryRate_resolvesToRR() {
		assertEquals(Collections.singletonList("RR"),
		    AbstractRetrievalQualityEvalTest.synonymsForText(
		        "Clinical observation: (2025-01-01) Test — Respiratory Rate: 18.0 breaths/min"));
	}

	@Test
	public void systolicBloodPressure_resolvesToSbpAndBp() {
		assertEquals(Arrays.asList("SBP", "BP"),
		    AbstractRetrievalQualityEvalTest.synonymsForText(
		        "Clinical observation: (2025-01-01) Test — Systolic Blood Pressure: 120 mmHg"));
	}

	@Test
	public void diastolicBloodPressure_resolvesToDbpAndBp() {
		// Diastolic must not collide with Systolic on substring containment — both contain
		// "Blood Pressure" but the map keys are full preferred names.
		assertEquals(Arrays.asList("DBP", "BP"),
		    AbstractRetrievalQualityEvalTest.synonymsForText(
		        "Clinical observation: (2025-01-01) Test — Diastolic Blood Pressure: 80 mmHg"));
	}

	@Test
	public void cd4Count_resolvesToCd4() {
		assertEquals(Collections.singletonList("CD4"),
		    AbstractRetrievalQualityEvalTest.synonymsForText(
		        "Clinical observation: (2025-01-01) Test — CD4 Count: 988.0 cells/mmL"));
	}

	@Test
	public void unknownConcept_returnsEmpty() {
		assertTrue(AbstractRetrievalQualityEvalTest.synonymsForText(
		    "Clinical observation: (2025-01-01) Test — Unrecognised: 1.0 unit").isEmpty());
	}

	@Test
	public void textWithoutConceptDelimiter_returnsEmpty() {
		assertTrue(AbstractRetrievalQualityEvalTest.synonymsForText("free-form text").isEmpty());
	}

	@Test
	public void nullText_returnsEmpty() {
		// synonymsForText is protected static and reachable from any same-package caller; the null
		// guard keeps a defensive caller from NPE-ing on String.contains.
		assertTrue(AbstractRetrievalQualityEvalTest.synonymsForText(null).isEmpty());
	}
}
