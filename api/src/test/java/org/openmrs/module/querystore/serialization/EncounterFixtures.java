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

import java.util.HashSet;
import java.util.Set;

import org.openmrs.Encounter;
import org.openmrs.EncounterProvider;
import org.openmrs.Provider;

/**
 * Shared {@link Encounter} builders for serializer unit tests. The single-active-provider pattern
 * (one {@link EncounterProvider} wrapping a {@link Provider}) is needed by every order- and
 * dispense-family test that exercises orderer-overrides-encounter-provider semantics. Static-import
 * from test classes.
 */
final class EncounterFixtures {

	private EncounterFixtures() {
	}

	/**
	 * Builds an {@link Encounter} with the given UUID and a single active
	 * {@link EncounterProvider} wrapping {@code provider}. Use this when the test only cares about
	 * the encounter's first active provider — additional providers, roles, or void state need a
	 * direct construction.
	 */
	static Encounter encounterWithProvider(String uuid, Provider provider) {
		Encounter enc = new Encounter();
		enc.setUuid(uuid);
		EncounterProvider ep = new EncounterProvider();
		ep.setProvider(provider);
		Set<EncounterProvider> providers = new HashSet<>();
		providers.add(ep);
		enc.setEncounterProviders(providers);
		return enc;
	}
}
