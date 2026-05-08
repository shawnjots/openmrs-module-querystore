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

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Backend capability descriptor consulted by the service layer before dispatching a call. Backends
 * never silently downgrade: the service layer reads capabilities, logs a warning when an
 * operation is supported but not at the requested scale, and the backend either executes at
 * whatever cost it can or throws {@link UnsupportedBackendOperationException} for hard misses.
 */
public final class BackendCapabilities {

	private final boolean supportsKnn;

	private final boolean supportsHybridNative;

	private final boolean supportsCrossPatientKnnAtScale;

	private final int recommendedMaxCorpusSize;

	private final Set<Filter.Kind> supportedFilters;

	public BackendCapabilities(boolean supportsKnn, boolean supportsHybridNative,
	        boolean supportsCrossPatientKnnAtScale, int recommendedMaxCorpusSize,
	        Set<Filter.Kind> supportedFilters) {
		this.supportsKnn = supportsKnn;
		this.supportsHybridNative = supportsHybridNative;
		this.supportsCrossPatientKnnAtScale = supportsCrossPatientKnnAtScale;
		this.recommendedMaxCorpusSize = recommendedMaxCorpusSize;
		this.supportedFilters = Collections.unmodifiableSet(EnumSet.copyOf(supportedFilters));
	}

	public boolean supportsKnn() {
		return supportsKnn;
	}

	public boolean supportsHybridNative() {
		return supportsHybridNative;
	}

	public boolean supportsCrossPatientKnnAtScale() {
		return supportsCrossPatientKnnAtScale;
	}

	public int getRecommendedMaxCorpusSize() {
		return recommendedMaxCorpusSize;
	}

	public Set<Filter.Kind> getSupportedFilters() {
		return supportedFilters;
	}
}
