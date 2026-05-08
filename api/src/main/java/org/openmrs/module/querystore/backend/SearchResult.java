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

public final class SearchResult {

	private final List<Hit> hits;

	public SearchResult(List<Hit> hits) {
		this.hits = Collections.unmodifiableList(new ArrayList<>(hits));
	}

	public List<Hit> getHits() {
		return hits;
	}

	public static SearchResult empty() {
		return new SearchResult(Collections.emptyList());
	}
}
