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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Test;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.querystore.bootstrap.BootstrapLauncher;
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

	@Test
	public void reindex_launchesBootstrapAndReturns202_whenScopeAll() {
		authenticate();
		RecordingLauncher launcher = new RecordingLauncher(true);
		controller.setBootstrapLauncher(launcher);

		ResponseEntity<Object> response = controller.reindex(Collections.singletonMap("scope", "all"));

		assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
		assertEquals("scope:all must trigger exactly one global bootstrap launch", 1, launcher.launches);
		assertEquals(Boolean.TRUE, ((Map<?, ?>) response.getBody()).get("accepted"));
	}

	@Test
	public void reindex_returns503_whenScopeAllButDaemonTokenUnavailable() {
		authenticate();
		// launchAsync returns false when no daemon token is wired — the server cannot start the
		// async backfill, so the caller must see a 503, not a misleading 202.
		RecordingLauncher launcher = new RecordingLauncher(false);
		controller.setBootstrapLauncher(launcher);

		ResponseEntity<Object> response = controller.reindex(Collections.singletonMap("scope", "all"));

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
		assertEquals(1, launcher.launches);
		assertNotNull(errorOf(response));
	}

	@Test
	public void reindex_returns400_whenNeitherPatientNorScopeProvided() {
		authenticate();
		// Absence of both must stay a clean rejection — it must NOT silently become a global
		// reindex (that is exactly the accidental-trigger footgun scope:"all" guards against).
		ResponseEntity<Object> response = controller.reindex(Collections.<String, String> emptyMap());
		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		assertNotNull(errorOf(response));
	}

	@Test
	public void reindex_returns400_whenScopeUnknown() {
		authenticate();
		// An unrecognised scope is a client error, never a fall-through to per-patient or global.
		ResponseEntity<Object> response = controller.reindex(Collections.singletonMap("scope", "everything"));
		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		assertNotNull(errorOf(response));
	}

	@Test(expected = ContextAuthenticationException.class)
	public void reindex_requiresManageGlobalPropertiesPrivilege() {
		// The endpoint gates an expensive, destructive operation; a caller lacking the privilege must
		// be rejected up front, before any reindex is launched. Pins that the requirePrivilege gate
		// fires — without this, deleting the gate would let any caller trigger a global reindex.
		Context.setUserContext(new UserContext(null) {

			@Override
			public boolean hasPrivilege(String privilege) {
				return false;
			}
		});
		controller.reindex(Collections.singletonMap("scope", "all"));
	}

	@Test
	public void reindex_returns400_whenBothPatientAndScopeAllProvided() {
		authenticate();
		// Ambiguous intent must be rejected, not silently resolved to the expensive global reindex.
		RecordingLauncher launcher = new RecordingLauncher(true);
		controller.setBootstrapLauncher(launcher);
		Map<String, String> body = new HashMap<String, String>();
		body.put("scope", "all");
		body.put("patient", "patient-uuid");

		ResponseEntity<Object> response = controller.reindex(body);

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		assertEquals("an ambiguous patient+scope request must not launch a global reindex", 0, launcher.launches);
	}

	@Test
	public void reindex_acceptsMixedCaseScopeAll() {
		authenticate();
		// A hand-typed "ALL" expresses the same intent; it must not be rejected as an unknown scope.
		RecordingLauncher launcher = new RecordingLauncher(true);
		controller.setBootstrapLauncher(launcher);

		ResponseEntity<Object> response = controller.reindex(Collections.singletonMap("scope", "ALL"));

		assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
		assertEquals(1, launcher.launches);
	}

	@Test
	public void reindex_treatsBlankScopeAsAbsent_andDoesNotLaunch() {
		authenticate();
		// A blank/whitespace scope is not a global-reindex request; with no patient it is a 400,
		// and crucially it must not launch a bootstrap.
		RecordingLauncher launcher = new RecordingLauncher(true);
		controller.setBootstrapLauncher(launcher);
		ResponseEntity<Object> response = controller.reindex(Collections.singletonMap("scope", "   "));
		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		assertEquals("a blank scope must not launch a global reindex", 0, launcher.launches);
	}

	/** Authenticates the thread with a user that holds every privilege, so the controller's
	 *  up-front {@code Context.requirePrivilege} gate passes and the routing logic under test runs. */
	private static void authenticate() {
		Context.setUserContext(new UserContext(null) {

			@Override
			public User getAuthenticatedUser() {
				return new User();
			}

			@Override
			public boolean isAuthenticated() {
				return true;
			}

			@Override
			public boolean hasPrivilege(String privilege) {
				return true;
			}
		});
	}

	/** Records launch attempts and returns a canned result, so the controller's scope:"all" routing
	 *  is verified without wiring a daemon token or spawning a real bootstrap thread. */
	private static final class RecordingLauncher extends BootstrapLauncher {

		private final boolean result;

		private int launches = 0;

		private RecordingLauncher(boolean result) {
			this.result = result;
		}

		@Override
		public boolean launchAsync() {
			launches++;
			return result;
		}
	}

	private static String errorOf(ResponseEntity<Object> response) {
		return (String) ((Map<?, ?>) response.getBody()).get("error");
	}
}
