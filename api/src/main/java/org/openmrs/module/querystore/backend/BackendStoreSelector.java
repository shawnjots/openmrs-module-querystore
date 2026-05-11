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

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.module.querystore.QueryStoreConstants;

/**
 * Picks the active {@link BackendStore} based on the {@code querystore.backend} global property per
 * ADR Decision 3. Spring wires every candidate backend bean into this selector at module startup;
 * the selector reads the GP via {@link AdministrationService} and exposes the chosen one as the
 * single {@code querystore.backend} bean consumed by {@code QueryStoreService}. Unknown values
 * fall back to {@link QueryStoreConstants#DEFAULT_BACKEND} with a logged warning rather than
 * failing module startup, so an admin can correct the GP and restart.
 */
public class BackendStoreSelector {

	private static final Log log = LogFactory.getLog(BackendStoreSelector.class);

	private final Map<String, BackendStore> candidates;

	public BackendStoreSelector(Map<String, BackendStore> candidates) {
		// Normalize keys to lower case at construction time so the GP-lookup branch (also lower
		// cased) doesn't have to care how the wiring spelled them.
		Map<String, BackendStore> normalized = new LinkedHashMap<>(candidates.size());
		for (Map.Entry<String, BackendStore> entry : candidates.entrySet()) {
			normalized.put(entry.getKey().toLowerCase(), entry.getValue());
		}
		this.candidates = normalized;
	}

	/**
	 * Resolves the active backend at bean-construction time. Called via {@code factory-method} from
	 * Spring; the returned instance becomes the {@code querystore.backend} bean wired into the
	 * service layer.
	 */
	public BackendStore getStore() {
		String chosen = resolveBackendName();
		BackendStore store = candidates.get(chosen);
		if (store == null) {
			log.warn("Unknown querystore.backend='" + chosen + "'; falling back to "
			        + QueryStoreConstants.DEFAULT_BACKEND);
			store = candidates.get(QueryStoreConstants.DEFAULT_BACKEND);
		}
		if (store == null) {
			throw new IllegalStateException(
			        "No BackendStore candidate registered for querystore.backend=" + chosen
			                + " and no default candidate '" + QueryStoreConstants.DEFAULT_BACKEND + "' wired");
		}
		log.info("Query Store backend resolved: " + chosen);
		return store;
	}

	private static String resolveBackendName() {
		// AdministrationService is registered with the core context before any module context
		// loads, so this call is safe at module bean init — but defend against test contexts
		// that bypass the framework by treating any lookup failure as "use the default".
		try {
			AdministrationService admin = Context.getAdministrationService();
			if (admin != null) {
				String value = admin.getGlobalProperty(QueryStoreConstants.GP_BACKEND,
				    QueryStoreConstants.DEFAULT_BACKEND);
				if (value != null && !value.trim().isEmpty()) {
					return value.trim().toLowerCase();
				}
			}
		}
		catch (RuntimeException e) {
			log.warn("Could not read " + QueryStoreConstants.GP_BACKEND + " GP; using default", e);
		}
		return QueryStoreConstants.DEFAULT_BACKEND;
	}
}
