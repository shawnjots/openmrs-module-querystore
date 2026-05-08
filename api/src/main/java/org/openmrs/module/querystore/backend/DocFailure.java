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

public final class DocFailure {

	private final String resourceType;

	private final String resourceUuid;

	private final String errorMessage;

	private final boolean retryable;

	public DocFailure(String resourceType, String resourceUuid, String errorMessage, boolean retryable) {
		this.resourceType = resourceType;
		this.resourceUuid = resourceUuid;
		this.errorMessage = errorMessage;
		this.retryable = retryable;
	}

	public String getResourceType() {
		return resourceType;
	}

	public String getResourceUuid() {
		return resourceUuid;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public boolean isRetryable() {
		return retryable;
	}
}
