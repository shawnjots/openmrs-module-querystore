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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.module.querystore.QueryStoreConstants;

/**
 * The active sync path, selected by the {@code querystore.syncMode} global property (ADR Decision
 * 12, "Runtime sync-mode selection"). Two booleans because {@link #BOTH} enables both paths during
 * the parity-verification overlap:
 *
 * <ul>
 *   <li>{@link #AOP} — migration-bridge advice only (the opt-out, and the failure-safe fallback).</li>
 *   <li>{@link #EVENTS} — consume core's #6084 {@code *ServiceEvent}s; the bridge gates off (the default).</li>
 *   <li>{@link #BOTH} — both, for parity verification only; double embedding cost, not a steady
 *       state.</li>
 * </ul>
 */
public enum SyncMode {

	AOP(true, false),
	EVENTS(false, true),
	BOTH(true, true);

	private static final Log log = LogFactory.getLog(SyncMode.class);

	private final boolean aopEnabled;

	private final boolean eventsEnabled;

	SyncMode(boolean aopEnabled, boolean eventsEnabled) {
		this.aopEnabled = aopEnabled;
		this.eventsEnabled = eventsEnabled;
	}

	public boolean aopEnabled() {
		return aopEnabled;
	}

	public boolean eventsEnabled() {
		return eventsEnabled;
	}

	/**
	 * Parses a {@code querystore.syncMode} value. Null, blank, or unrecognized values fall back to
	 * {@link #AOP} — the conservative, always-available path — rather than the configured default
	 * ({@code events}): a malformed value shouldn't silently activate the events path, and enabling
	 * neither would stall the projection (ADR Decision 12: a stale projection is the worst failure
	 * mode). Unrecognized non-blank values are logged so a typo is visible.
	 */
	public static SyncMode parse(String value) {
		if (value == null || value.trim().isEmpty()) {
			return AOP;
		}
		switch (value.trim().toLowerCase()) {
			case QueryStoreConstants.SYNC_MODE_AOP:
				return AOP;
			case QueryStoreConstants.SYNC_MODE_EVENTS:
				return EVENTS;
			case QueryStoreConstants.SYNC_MODE_BOTH:
				return BOTH;
			default:
				log.warn("Unknown " + QueryStoreConstants.GP_SYNC_MODE + "='" + value
				        + "'; falling back to " + QueryStoreConstants.DEFAULT_SYNC_MODE);
				return AOP;
		}
	}
}
