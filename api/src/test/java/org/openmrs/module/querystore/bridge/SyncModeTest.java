/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.openmrs.module.querystore.QueryStoreConstants;

public class SyncModeTest {

	@Test
	public void defaultSyncMode_isEvents() {
		// The configured default (GP absent) is events — the consumer is the active path; the bridge
		// is the opt-out. Locks the post-verification flip (ADR Decision 12 trajectory).
		assertEquals(SyncMode.EVENTS, SyncMode.parse(QueryStoreConstants.DEFAULT_SYNC_MODE));
	}

	@Test
	public void parse_recognizesEachMode() {
		assertEquals(SyncMode.AOP, SyncMode.parse("aop"));
		assertEquals(SyncMode.EVENTS, SyncMode.parse("events"));
		assertEquals(SyncMode.BOTH, SyncMode.parse("both"));
	}

	@Test
	public void parse_isCaseAndWhitespaceInsensitive() {
		assertEquals(SyncMode.EVENTS, SyncMode.parse("  EVENTS "));
	}

	@Test
	public void parse_nullBlankOrUnknown_fallsBackToAop() {
		assertEquals(SyncMode.AOP, SyncMode.parse(null));
		assertEquals(SyncMode.AOP, SyncMode.parse("   "));
		assertEquals(SyncMode.AOP, SyncMode.parse("garbage"));
	}

	@Test
	public void flags_matchTheIntendedPathPerMode() {
		assertTrue("aop runs the bridge", SyncMode.AOP.aopEnabled());
		assertFalse("aop does not run events", SyncMode.AOP.eventsEnabled());

		assertFalse("events gates the bridge off", SyncMode.EVENTS.aopEnabled());
		assertTrue("events runs the consumer", SyncMode.EVENTS.eventsEnabled());

		assertTrue("both runs the bridge", SyncMode.BOTH.aopEnabled());
		assertTrue("both runs the consumer", SyncMode.BOTH.eventsEnabled());
	}
}
