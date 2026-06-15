/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.bridge;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.openmrs.Obs;
import org.openmrs.api.context.Context;
import org.openmrs.module.querystore.serialization.ObsRecordSerializer;

/**
 * Migration-bridge advice on {@link org.openmrs.api.ObsService} (ADR Decision 12 "Migration
 * bridge"). For each {@code saveObs / voidObs / unvoidObs / purgeObs} call this projects the obs
 * (and its group members, walked recursively) through the same {@code serialize → embed → index}
 * pipeline the events-first handlers will drive once core publishes obs events.
 *
 * <p><b>Removal marker.</b> Delete this class — and its {@code <advice>} entry in
 * {@code omod/src/main/resources/config.xml} — when the events-first subscriber for obs ships and
 * has been verified at parity.
 * <pre>Removal trigger: TBD (events-first obs subscriber)</pre>
 *
 * <p><b>Group obs.</b> Obs is the one entity type that recursively references same-type children —
 * group members are themselves obs. The tree-walk that flattens them lives on
 * {@link ObsRecordSerializer#collectTree(Obs)} (shared by both sync paths via
 * {@link RecordProjector}), so the per-node voided policy partitions each node independently (a
 * {@code saveObs} on a parent whose member was newly voided routes the voided member to delete while
 * indexing the live siblings).
 */
public class ObsIndexingAdvice extends AbstractIndexingAdvice<Obs> {

	static final Set<String> TRIGGER_METHODS = new HashSet<>(
	        Arrays.asList("saveObs", "voidObs", "unvoidObs", "purgeObs"));

	static final Set<String> PURGE_METHODS = Collections.singleton("purgeObs");

	@Override
	protected Class<Obs> getSupportedType() {
		return Obs.class;
	}

	@Override
	protected ObsRecordSerializer serializer() {
		return Context.getRegisteredComponent("querystore.serializer.obs", ObsRecordSerializer.class);
	}

	@Override
	protected Set<String> triggerMethods() {
		return TRIGGER_METHODS;
	}

	@Override
	protected Set<String> purgeMethods() {
		return PURGE_METHODS;
	}
}
