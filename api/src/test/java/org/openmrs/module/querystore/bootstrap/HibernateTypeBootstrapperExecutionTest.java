/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.bootstrap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.util.List;

import org.junit.Test;
import org.openmrs.Diagnosis;
import org.openmrs.DrugOrder;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.querystore.serialization.DiagnosisRecordSerializer;
import org.openmrs.module.querystore.serialization.DrugOrderRecordSerializer;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Executes the REAL {@link HibernateTypeBootstrapper} page-fetch HQL against an in-memory OpenMRS
 * database. This is the only test that actually runs the query: {@code BootstrappersTest} pins the
 * HQL <em>string</em>, and the {@code FakeBootstrapper}-based tests stub {@code fetchPage}. It guards
 * the one thing those can't — that the orphan-guard HQL is valid, runnable Hibernate, not just a
 * correct-looking string.
 *
 * <p>{@link DrugOrder} is the subject because the standard test dataset ships drug orders with valid
 * patient and encounter FKs, and {@code DrugOrderBootstrapper} declares the encounter guard
 * ({@code Order.encounter} is {@code not-null} + eager). So its scan exercises BOTH implicit-join
 * guards in one query — {@code e.patient.uuid IS NOT NULL AND e.encounter.uuid IS NOT NULL} — and
 * asserting the valid rows survive proves the guard doesn't over-drop. A malformed builder (bad
 * placement, dangling clause, two-join HQL Hibernate can't parse) would throw here instead.
 */
public class HibernateTypeBootstrapperExecutionTest extends BaseModuleContextSensitiveTest {

	@Autowired
	private DbSessionFactory sessionFactory;

	@Test
	public void globalFetchPage_runsTwoJoinGuardHql_andRetainsValidRows() throws Exception {
		executeDataSet("org/openmrs/include/standardTestDataset.xml");
		DrugOrderBootstrapper bootstrapper =
		        new DrugOrderBootstrapper(new DrugOrderRecordSerializer(), sessionFactory);

		// Runs: FROM DrugOrder e WHERE e.voided = false AND e.patient.uuid IS NOT NULL
		//       AND e.encounter.uuid IS NOT NULL ORDER BY e.dateCreated ASC, e.uuid ASC
		List<DrugOrder> page = bootstrapper.fetchPage(null, null, 200);

		assertFalse("standard dataset has drug orders with valid patient + encounter FKs; the "
		        + "encounter + patient guards must retain them, not drop them", page.isEmpty());
	}

	@Test
	public void perPatientFetchPage_runsTwoJoinGuardHql() throws Exception {
		// reindexPatient's path. A real patient UUID keeps the query parameter valid; the assertion is
		// only that the per-patient two-join guard HQL parses and executes (the patient may own no drug
		// orders, in which case an empty result is correct — what matters is it does not throw).
		executeDataSet("org/openmrs/include/standardTestDataset.xml");
		Patient anyPatient = Context.getPatientService().getAllPatients().get(0);
		DrugOrderBootstrapper bootstrapper =
		        new DrugOrderBootstrapper(new DrugOrderRecordSerializer(), sessionFactory);

		bootstrapper.fetchPageForPatient(anyPatient.getUuid(), null, null, 200);
	}

	@Test
	public void globalFetchPage_runsForDiagnosis_theTypeThatFailedOnTheDemo() throws Exception {
		// Diagnosis is the type that FAILED on the demo (FetchNotFoundException at this fetch). The
		// standard dataset ships no diagnoses, so the result is empty — but running it proves the
		// FIXED diagnosis HQL (COALESCE(dateChanged, dateCreated) cursor + the encounter guard) parses
		// and executes rather than throwing, which is the regression this whole change exists to prevent.
		executeDataSet("org/openmrs/include/standardTestDataset.xml");
		DiagnosisBootstrapper bootstrapper =
		        new DiagnosisBootstrapper(new DiagnosisRecordSerializer(), sessionFactory);

		List<Diagnosis> page = bootstrapper.fetchPage(null, null, 200);

		assertNotNull("the fixed diagnosis fetch HQL must run and return a (possibly empty) page", page);
	}
}
