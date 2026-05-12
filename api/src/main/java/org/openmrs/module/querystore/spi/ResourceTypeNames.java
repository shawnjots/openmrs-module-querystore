/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.spi;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.openmrs.module.querystore.QueryStoreConstants;

/**
 * Resource-type name validation for {@link ResourceTypeProvider} per ADR Decision 13. A
 * provider-supplied name must be {@code <moduleid>_<type>} — both segments {@code [a-z][a-z0-9]*},
 * the {@code <type>} segment may contain further underscores (e.g., {@code billing_payment_method}).
 * Unprefixed names are reserved for the core types querystore itself indexes; providers must use a
 * moduleid prefix so the name alone unambiguously signals provenance.
 */
public final class ResourceTypeNames {

	// Two-or-more [a-z][a-z0-9]* segments joined by single underscores: moduleid_typeword(_typeword)*.
	// Disallows leading/trailing underscores and consecutive underscores (a `__` looks like a missing
	// segment rather than a legal multi-word type name).
	static final Pattern PROVIDED_PATTERN = Pattern.compile("^[a-z][a-z0-9]*(_[a-z][a-z0-9]*)+$");

	/** Unprefixed names querystore reserves for its own core types. Derived from the
	 *  {@code INDEX_*} constants in {@link QueryStoreConstants} (single source of truth — adding
	 *  a 13th core index there propagates here automatically). */
	static final Set<String> CORE_RESERVED;

	static {
		String prefix = QueryStoreConstants.INDEX_PREFIX;
		Set<String> reserved = new HashSet<>();
		reserved.add(stripPrefix(QueryStoreConstants.INDEX_OBS, prefix));
		reserved.add(stripPrefix(QueryStoreConstants.INDEX_ENCOUNTER, prefix));
		reserved.add(stripPrefix(QueryStoreConstants.INDEX_VISIT, prefix));
		reserved.add(stripPrefix(QueryStoreConstants.INDEX_PATIENT, prefix));
		reserved.add(stripPrefix(QueryStoreConstants.INDEX_CONDITION, prefix));
		reserved.add(stripPrefix(QueryStoreConstants.INDEX_DIAGNOSIS, prefix));
		reserved.add(stripPrefix(QueryStoreConstants.INDEX_ALLERGY, prefix));
		reserved.add(stripPrefix(QueryStoreConstants.INDEX_DRUG_ORDER, prefix));
		reserved.add(stripPrefix(QueryStoreConstants.INDEX_TEST_ORDER, prefix));
		reserved.add(stripPrefix(QueryStoreConstants.INDEX_REFERRAL_ORDER, prefix));
		reserved.add(stripPrefix(QueryStoreConstants.INDEX_MEDICATION_DISPENSE, prefix));
		reserved.add(stripPrefix(QueryStoreConstants.INDEX_PROGRAM, prefix));
		CORE_RESERVED = Collections.unmodifiableSet(reserved);
	}

	private static String stripPrefix(String index, String prefix) {
		return index.startsWith(prefix) ? index.substring(prefix.length()) : index;
	}

	/**
	 * Validates that {@code name} is a legal provider-supplied resource type. Throws
	 * {@link IllegalArgumentException} when it doesn't match {@code <moduleid>_<type>} or collides
	 * with a core-reserved name. The collision check carries real weight because four core names
	 * already contain underscores ({@code drug_order}, {@code test_order}, {@code referral_order},
	 * {@code medication_dispense}) and so would pass the pattern alone; the reserved-set check is
	 * what actually prevents a provider from claiming them.
	 */
	public static void validateProvided(String name) {
		if (name == null || !PROVIDED_PATTERN.matcher(name).matches()) {
			throw new IllegalArgumentException(
			        "Resource type name '" + name + "' must match <moduleid>_<type> "
			                + "(both segments [a-z][a-z0-9]*, e.g., 'appointments_appointment')");
		}
		if (CORE_RESERVED.contains(name)) {
			throw new IllegalArgumentException(
			        "Resource type name '" + name + "' is reserved for a querystore core type");
		}
	}

	private ResourceTypeNames() {
	}
}
