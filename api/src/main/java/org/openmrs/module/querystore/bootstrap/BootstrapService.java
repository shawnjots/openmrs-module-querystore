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

import java.util.List;

import org.openmrs.api.OpenmrsService;

/**
 * Entry point for the initial-backfill path (ADR open question on bootstrap). Walks core's
 * transactional data per resource type and writes documents to the read store; concurrent steady-
 * state writes (AOP bridge or event handlers) are version-protected by the Decision 3 invariant
 * so the freshest projection always wins. Invocation is admin-triggered; this service does not
 * auto-run from {@code QueryStoreActivator}.
 */
public interface BootstrapService extends OpenmrsService {

	/** Runs backfill for every registered resource type sequentially. A per-type failure does not
	 *  abort the rest; failed types stay in {@link BootstrapStatus#FAILED} and can be retried. */
	void bootstrap();

	/** Runs backfill for a single resource type. Resumes from the persisted cursor if a prior
	 *  run was interrupted; restarts from the beginning only if no progress row exists. */
	void bootstrap(String resourceType);

	List<BootstrapProgress> getStatus();

	BootstrapProgress getStatus(String resourceType);
}
