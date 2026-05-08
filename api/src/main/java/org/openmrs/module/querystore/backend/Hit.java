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

import org.openmrs.module.querystore.model.QueryDocument;

/**
 * One result row from a search call. {@code rank} is the canonical cross-backend signal (1-based,
 * monotone). {@code rawScore} is preserved for telemetry but is explicitly not comparable across
 * backends or across queries on the same backend.
 */
public final class Hit {

	private final QueryDocument document;

	private final double rawScore;

	private final int rank;

	public Hit(QueryDocument document, double rawScore, int rank) {
		this.document = document;
		this.rawScore = rawScore;
		this.rank = rank;
	}

	public QueryDocument getDocument() {
		return document;
	}

	public double getRawScore() {
		return rawScore;
	}

	public int getRank() {
		return rank;
	}
}
