/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.Test;
import org.openmrs.Concept;
import org.openmrs.ConceptDescription;
import org.openmrs.ConceptName;

public class ConceptNameUtilTest {

	@Test
	public void getPreferredName_returnsEmpty_whenConceptNull() {
		assertEquals("", ConceptNameUtil.getPreferredName(null));
	}

	@Test
	public void getPreferredName_resolvesAcrossLocaleVariants() {
		// Concept name tagged "en" while default deployment locale is en_GB. The OpenMRS-core
		// fallback chain (Concept.getName(Locale)) does language-level matching when the strict
		// locale lookup fails — this test locks that assumption down.
		Concept c = new Concept();
		c.addName(preferredName("Hypertension"));
		assertEquals("Hypertension", ConceptNameUtil.getPreferredName(c));
	}

	@Test
	public void getSynonyms_returnsEmpty_whenConceptNull() {
		assertTrue(ConceptNameUtil.getSynonyms(null).isEmpty());
	}

	@Test
	public void getSynonyms_capsAtThree_alphabeticallyFirst() {
		Concept c = new Concept();
		c.addName(preferredName("Primary"));
		c.addName(name("epsilon"));
		c.addName(name("delta"));
		c.addName(name("gamma"));
		c.addName(name("beta"));
		c.addName(name("alpha"));

		List<String> synonyms = ConceptNameUtil.getSynonyms(c);
		assertEquals(Arrays.asList("alpha", "beta", "delta"), synonyms);
	}

	@Test
	public void getSynonyms_excludesPreferredName() {
		Concept c = new Concept();
		c.addName(preferredName("Hypertension"));
		c.addName(name("HTN"));

		assertEquals(Arrays.asList("HTN"), ConceptNameUtil.getSynonyms(c));
	}

	@Test
	public void getSynonyms_dropsDuplicateOfPreferredAcrossNameInstances() {
		// Same name string can appear twice in concept.getNames() (e.g., once preferred, once as
		// a synonym in the same dictionary). Our string-level dedupe should drop the duplicate.
		Concept c = new Concept();
		c.addName(preferredName("Hypertension"));
		c.addName(name("Hypertension"));
		c.addName(name("HTN"));

		assertEquals(Arrays.asList("HTN"), ConceptNameUtil.getSynonyms(c));
	}

	@Test
	public void getSynonyms_filtersByLanguage() {
		Concept c = new Concept();
		c.addName(preferredName("Hypertension"));
		c.addName(name("HTN"));
		c.addName(localizedName("Hypertonie", Locale.GERMAN));

		// Default locale resolves to en_GB (no Context); German synonym is filtered out.
		assertEquals(Arrays.asList("HTN"), ConceptNameUtil.getSynonyms(c));
	}

	@Test
	public void getDescription_returnsEmpty_whenConceptNull() {
		assertEquals("", ConceptNameUtil.getDescription(null));
	}

	@Test
	public void getDescription_returnsEmpty_whenNoDescriptions() {
		// Common case in real data: ~40-60% of CIEL concepts have no description. Empty-string
		// return means putConceptFields skips writing the metadata key — keeps the doc compact.
		Concept c = new Concept();
		c.addName(preferredName("Asthma"));
		assertEquals("", ConceptNameUtil.getDescription(c));
	}

	@Test
	public void getDescription_returnsLanguageMatch() {
		Concept c = new Concept();
		c.addName(preferredName("Hypertension"));
		c.addDescription(description("Persistently high arterial blood pressure.", Locale.ENGLISH));
		assertEquals("Persistently high arterial blood pressure.",
				ConceptNameUtil.getDescription(c));
	}

	@Test
	public void getDescription_fallsBackToAnyLocale_whenNoLanguageMatch() {
		// CIEL frequently ships only "en" descriptions; deployments may run with non-English
		// default locales. Returning the only available description beats no description at all
		// for BM25 vocabulary — the alternative is dropping retrieval signal entirely.
		Concept c = new Concept();
		c.addName(preferredName("Hypertension"));
		c.addDescription(description("Hypertonie - persistierend hoher Blutdruck.", Locale.GERMAN));
		// Default locale resolves to en_GB (no Context); no language match → fall back to the only
		// available description (German), keeping the BM25 signal rather than dropping it.
		assertEquals("Hypertonie - persistierend hoher Blutdruck.",
				ConceptNameUtil.getDescription(c));
	}

	@Test
	public void getDescription_prefersLanguageMatchOverFirstEntry() {
		// When a concept has descriptions in multiple locales, the active-language match wins
		// over insertion order — locking the per-locale routing down so a future change to
		// LinkedHashSet ordering can't silently swap which description gets indexed.
		Concept c = new Concept();
		c.addName(preferredName("Hypertension"));
		c.addDescription(description("Hypertonie.", Locale.GERMAN));
		c.addDescription(description("Persistently high arterial blood pressure.", Locale.ENGLISH));
		c.addDescription(description("Hypertension artérielle.", Locale.FRENCH));
		assertEquals("Persistently high arterial blood pressure.",
				ConceptNameUtil.getDescription(c));
	}

	@Test
	public void getDescription_skipsNullAndEmptyTextEntries() {
		// Real OpenMRS data has been seen with placeholder/null description rows. Skipping them
		// and continuing the scan ensures a usable description from another row is still found.
		Concept c = new Concept();
		c.addName(preferredName("Hypertension"));
		c.addDescription(description(null, Locale.ENGLISH));
		c.addDescription(description("", Locale.ENGLISH));
		c.addDescription(description("Persistently high arterial blood pressure.", Locale.ENGLISH));
		assertEquals("Persistently high arterial blood pressure.",
				ConceptNameUtil.getDescription(c));
	}

	private static ConceptDescription description(String text, Locale locale) {
		ConceptDescription d = new ConceptDescription();
		d.setDescription(text);
		d.setLocale(locale);
		return d;
	}

	private static ConceptName name(String text) {
		return localizedName(text, Locale.ENGLISH);
	}

	private static ConceptName preferredName(String text) {
		ConceptName cn = name(text);
		cn.setLocalePreferred(Boolean.TRUE);
		return cn;
	}

	private static ConceptName localizedName(String text, Locale locale) {
		ConceptName cn = new ConceptName();
		cn.setName(text);
		cn.setLocale(locale);
		return cn;
	}
}
