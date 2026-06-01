/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.web.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Map;

import org.junit.After;
import org.junit.Test;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.UserContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * Guards the controller's {@code @ExceptionHandler} mapping: a thrown exception must surface as a
 * proper status with a clean {@code {"error": ...}} body, never the framework default of HTTP 200
 * carrying a serialized stack trace.
 *
 * <p>The handlers use no autowired state, so the controller is instantiated directly — no Spring
 * context is loaded. {@code handleAuthFailure} branches on {@link Context#isAuthenticated()}, which
 * reads the thread-bound {@link UserContext}; the tests set one directly to exercise both branches.
 * The HTTP routing that dispatches each exception type to its handler is verified end-to-end against
 * a live server.
 */
public class QueryStoreRestControllerTest {

	private final QueryStoreRestController controller = new QueryStoreRestController();

	@After
	public void clearContext() {
		Context.clearUserContext();
	}

	@Test
	public void handleAuthFailure_shouldReturn401WhenUnauthenticated() {
		Context.setUserContext(new UserContext(null)); // no authenticated user => not authenticated
		ResponseEntity<Object> response = controller.handleAuthFailure(new APIAuthenticationException("denied"));
		assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
		assertEquals("Authentication required", errorOf(response));
	}

	@Test
	public void handleAuthFailure_shouldReturn403WhenAuthenticated() {
		// An authenticated caller that was denied a privilege => 403, not 401.
		Context.setUserContext(new UserContext(null) {

			@Override
			public User getAuthenticatedUser() {
				return new User();
			}
		});
		ResponseEntity<Object> response = controller.handleAuthFailure(new APIAuthenticationException("denied"));
		assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
		assertEquals("Insufficient privileges", errorOf(response));
	}

	@Test
	public void handleMalformedBody_shouldReturn400() {
		ResponseEntity<Object> response = controller.handleMalformedBody(
		        new HttpMessageNotReadableException("bad json"));
		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		assertEquals("Malformed request body", errorOf(response));
	}

	@Test
	public void handleUnexpected_shouldReturnCleanInternalServerError() {
		ResponseEntity<Object> response = controller.handleUnexpected(new RuntimeException("sensitive detail"));
		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
		Map<?, ?> body = (Map<?, ?>) response.getBody();
		assertNotNull(body);
		// Exactly {"error":"Internal error"} — size 1 proves no stack trace / detail leaked.
		assertEquals(1, body.size());
		assertEquals("Internal error", body.get("error"));
	}

	private static String errorOf(ResponseEntity<Object> response) {
		return (String) ((Map<?, ?>) response.getBody()).get("error");
	}
}
