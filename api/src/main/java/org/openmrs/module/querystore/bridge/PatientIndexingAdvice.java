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

import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.querystore.serialization.PatientRecordSerializer;

/**
 * Migration-bridge advice on the patient methods of {@link org.openmrs.api.PatientService}.
 *
 * <p>{@code mergePatients} is deliberately not in the trigger set — the patient-merge open
 * question (see {@code docs/adr.md#patient-merge-handling}) covers re-indexing and cross-patient
 * UUID rewrites on merge, which is structurally larger than this advice's scope. Until that lands,
 * a merged patient's read-store documents are reconciled by the bootstrap.
 *
 * <p><b>Bypass surface.</b> The cross-type purge sweep — now wired via
 * {@link PatientRecordSerializer#bulkDeletePatientUuidFor(org.openmrs.Patient)} and shared with the
 * events path through {@link RecordProjector} — fires only when a patient is removed through
 * {@link org.openmrs.api.PatientService#purgePatient(org.openmrs.Patient)}. A removal that bypasses
 * {@code PatientService} (raw SQL, a sibling module reaching into the DAO, or
 * {@link org.openmrs.api.PersonService#purgePerson(org.openmrs.Person)} on a person-who-is-a-patient
 * after the patient row was independently removed) is not advised; the patient's cross-type
 * read-store documents survive until the bootstrap reconciles them. Tracked under the "Sync
 * reliability and reconciliation" ADR open question; the canonical core entry point remains
 * {@code purgePatient}.
 * <pre>Removal trigger: TBD (events-first patient subscriber). The replacement must preserve BOTH
 * the per-row delete on querystore_patient AND the cross-type bulkDeleteByPatient sweep wired via
 * PatientRecordSerializer.bulkDeletePatientUuidFor — dropping either re-opens the PHI leak this
 * advice was extended to close.</pre>
 */
public class PatientIndexingAdvice extends AbstractIndexingAdvice<Patient> {

	static final Set<String> TRIGGER_METHODS = new HashSet<>(Arrays.asList(
	        "savePatient", "voidPatient", "unvoidPatient", "purgePatient"));

	static final Set<String> PURGE_METHODS = Collections.singleton("purgePatient");

	@Override
	protected Class<Patient> getSupportedType() {
		return Patient.class;
	}

	@Override
	protected PatientRecordSerializer serializer() {
		return Context.getRegisteredComponent("querystore.serializer.patient",
		        PatientRecordSerializer.class);
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
