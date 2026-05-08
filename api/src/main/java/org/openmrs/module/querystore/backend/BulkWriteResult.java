/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.backend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-document outcome of a bulk write. Surfaces partial failure explicitly so the sync pipeline
 * (Decision 12) and reconciliation can rely on it; chartsearchai's silent swallow of bulk
 * per-doc errors is exactly what this contract prevents.
 */
public final class BulkWriteResult {

	private final int totalRequested;

	private final int succeeded;

	private final List<DocFailure> failures;

	public BulkWriteResult(int totalRequested, int succeeded, List<DocFailure> failures) {
		this.totalRequested = totalRequested;
		this.succeeded = succeeded;
		this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
	}

	public int getTotalRequested() {
		return totalRequested;
	}

	public int getSucceeded() {
		return succeeded;
	}

	public List<DocFailure> getFailures() {
		return failures;
	}

	public boolean hasFailures() {
		return !failures.isEmpty();
	}
}
