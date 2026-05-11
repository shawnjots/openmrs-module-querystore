/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.bootstrap;

/**
 * Lifecycle state of a per-type backfill (ADR open question on initial bootstrap). A type is
 * {@code NOT_STARTED} until {@link BootstrapService#bootstrap(String)} runs it, transitions to
 * {@code RUNNING} for the duration of the scan, and ends in {@code COMPLETED} or {@code FAILED}.
 * Resuming a {@code FAILED} or interrupted run picks up from the persisted cursor.
 */
public enum BootstrapStatus {
	NOT_STARTED,
	RUNNING,
	COMPLETED,
	FAILED
}
