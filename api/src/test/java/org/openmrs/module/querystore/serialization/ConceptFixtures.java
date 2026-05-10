/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.serialization;

import java.util.Locale;

import org.openmrs.Concept;
import org.openmrs.ConceptName;

/**
 * Shared concept-fixture builders for serializer unit tests. Locale is fixed to
 * {@link Locale#ENGLISH} because the production locale resolution in
 * {@code ConceptNameUtil.resolveLocale} falls back to {@code en_GB} when no Context is open and
 * matches by language only; pinning to English keeps the tests deterministic across machines
 * without needing a Context. Static-import from test classes.
 */
final class ConceptFixtures {

	private ConceptFixtures() {
	}

	static Concept concept(String name) {
		Concept c = new Concept();
		c.addName(conceptName(name));
		return c;
	}

	static ConceptName conceptName(String name) {
		ConceptName cn = new ConceptName();
		cn.setName(name);
		cn.setLocale(Locale.ENGLISH);
		return cn;
	}

	static ConceptName preferredName(String name) {
		ConceptName cn = conceptName(name);
		cn.setLocalePreferred(Boolean.TRUE);
		return cn;
	}
}
