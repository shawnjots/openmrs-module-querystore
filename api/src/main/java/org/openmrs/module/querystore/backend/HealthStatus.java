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

public final class HealthStatus {

	public enum State {
		HEALTHY,
		DEGRADED,
		UNHEALTHY
	}

	private final State state;

	private final String message;

	public HealthStatus(State state, String message) {
		this.state = state;
		this.message = message;
	}

	public State getState() {
		return state;
	}

	public String getMessage() {
		return message;
	}

	public static HealthStatus healthy() {
		return new HealthStatus(State.HEALTHY, null);
	}

	public static HealthStatus unhealthy(String message) {
		return new HealthStatus(State.UNHEALTHY, message);
	}
}
