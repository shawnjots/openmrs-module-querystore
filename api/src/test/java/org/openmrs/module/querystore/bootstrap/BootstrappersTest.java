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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.openmrs.Allergy;
import org.openmrs.Condition;
import org.openmrs.Diagnosis;
import org.openmrs.DrugOrder;
import org.openmrs.Encounter;
import org.openmrs.MedicationDispense;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.ReferralOrder;
import org.openmrs.TestOrder;
import org.openmrs.Visit;
import org.openmrs.module.querystore.serialization.AllergyRecordSerializer;
import org.openmrs.module.querystore.serialization.ConditionRecordSerializer;
import org.openmrs.module.querystore.serialization.DiagnosisRecordSerializer;
import org.openmrs.module.querystore.serialization.DrugOrderRecordSerializer;
import org.openmrs.module.querystore.serialization.EncounterRecordSerializer;
import org.openmrs.module.querystore.serialization.MedicationDispenseRecordSerializer;
import org.openmrs.module.querystore.serialization.ObsRecordSerializer;
import org.openmrs.module.querystore.serialization.PatientProgramRecordSerializer;
import org.openmrs.module.querystore.serialization.PatientRecordSerializer;
import org.openmrs.module.querystore.serialization.ReferralOrderRecordSerializer;
import org.openmrs.module.querystore.serialization.TestOrderRecordSerializer;
import org.openmrs.module.querystore.serialization.VisitRecordSerializer;

/**
 * Per-type bootstrapper sanity: each of the 12 core resource types' bootstrapper resolves the right
 * resource_type and supports the right entity class. Together with the registration check this
 * pins the wiring contract for the consolidated HQL fetch path in {@link HibernateTypeBootstrapper}.
 */
public class BootstrappersTest {

	@Test
	public void each_bootstrapper_resolvesItsResourceTypeFromItsSerializer() {
		for (BootstrapperSpec s : specs()) {
			TypeBootstrapper<?> b = s.bootstrapper;
			assertEquals(s.expectedResourceType, b.getResourceType());
			assertEquals(s.expectedEntityClass, b.getSerializer().getSupportedType());
			assertSame(s.serializer, b.getSerializer());
		}
	}

	@Test
	public void hibernateBootstrapper_firstPageHql_includesEntityNameAndCursorExpr() {
		// The 12 leaf bootstrappers share a parameterized HQL; pin its shape for two representative
		// entities so a refactor of the HQL builder doesn't silently break the rest.
		String encHql = HibernateTypeBootstrapper.firstPageHql("Encounter",
		        "COALESCE(e.dateChanged, e.dateCreated)", "e.patient.uuid");
		assertTrue(encHql.contains("FROM Encounter e"));
		assertTrue(encHql.contains("WHERE e.voided = false"));
		assertTrue(encHql.contains("ORDER BY COALESCE(e.dateChanged, e.dateCreated) ASC, e.uuid ASC"));

		// Obs/Order subtypes use only dateCreated since Hibernate doesn't map dateChanged for them.
		String obsHql = HibernateTypeBootstrapper.firstPageHql("Obs", "e.dateCreated", "e.person.uuid");
		assertTrue(obsHql.contains("ORDER BY e.dateCreated ASC, e.uuid ASC"));
	}

	@Test
	public void hibernateBootstrapper_globalScan_excludesDanglingPatientRows() {
		// Orphan rows (a dangling patient/person FK, e.g. left by a SQL-dump load) must be filtered out
		// of the global scan — otherwise Hibernate eager-materializes the missing association during
		// q.list() and FetchNotFoundException fails the whole type. Navigating the patient association
		// forces the inner join that drops them. Pinned so a refactor can't silently reintroduce the
		// poison-page failure. Mirrors the per-patient scan, which is already orphan-safe.
		assertTrue("first-page scan excludes dangling-patient rows",
		        HibernateTypeBootstrapper.firstPageHql("Encounter", "e.dateCreated", "e.patient.uuid")
		                .contains("AND e.patient.uuid IS NOT NULL"));
		assertTrue("after-cursor scan excludes dangling-patient rows",
		        HibernateTypeBootstrapper.afterCursorHql("Encounter", "e.dateCreated", "e.patient.uuid")
		                .contains("AND e.patient.uuid IS NOT NULL"));
		// Obs scopes by person, not patient — its orphan filter must follow the same path it scans by.
		assertTrue("obs global scan excludes dangling-person rows",
		        HibernateTypeBootstrapper.firstPageHql("Obs", "e.dateCreated", "e.person.uuid")
		                .contains("AND e.person.uuid IS NOT NULL"));
	}

	@Test
	public void hibernateBootstrapper_globalScan_excludesDanglingEncounterRows_whenTypeDeclaresIt() {
		// Diagnosis.encounter is @ManyToOne(optional=false) and eager; a SQL-dump load can leave a
		// diagnosis with a valid patient but a dangling encounter FK. That survives the patient guard,
		// then q.list() eager-materializes the missing encounter and FetchNotFoundException fails the
		// WHOLE diagnosis type. The extra guard forces the inner join that drops the orphan at fetch,
		// alongside the patient guard. Without it, only the patient guard would be present here.
		String first = HibernateTypeBootstrapper.firstPageHql("Diagnosis",
		        "COALESCE(e.dateChanged, e.dateCreated)", "e.patient.uuid", "e.encounter.uuid");
		assertTrue("patient guard retained", first.contains("AND e.patient.uuid IS NOT NULL"));
		assertTrue("encounter guard added", first.contains("AND e.encounter.uuid IS NOT NULL"));

		String after = HibernateTypeBootstrapper.afterCursorHql("Diagnosis",
		        "COALESCE(e.dateChanged, e.dateCreated)", "e.patient.uuid", "e.encounter.uuid");
		assertTrue("encounter guard added", after.contains("AND e.encounter.uuid IS NOT NULL"));
		assertTrue("guard precedes the cursor group", after.contains("IS NOT NULL AND ("));
	}

	@Test
	public void hibernateBootstrapper_perPatientScan_excludesDanglingEncounterRows_whenTypeDeclaresIt() {
		// reindexPatient (per-patient path) also eager-materializes the encounter, so the same orphan
		// would fail a single-patient reindex without the guard on this path too.
		String first = HibernateTypeBootstrapper.firstPagePerPatientHql("Diagnosis",
		        "COALESCE(e.dateChanged, e.dateCreated)", "e.patient.uuid", "e.encounter.uuid");
		assertTrue("patient scope retained", first.contains("AND e.patient.uuid = :patientUuid"));
		assertTrue("encounter guard added", first.contains("AND e.encounter.uuid IS NOT NULL"));

		String after = HibernateTypeBootstrapper.afterCursorPerPatientHql("Diagnosis",
		        "COALESCE(e.dateChanged, e.dateCreated)", "e.patient.uuid", "e.encounter.uuid");
		assertTrue("encounter guard added", after.contains("AND e.encounter.uuid IS NOT NULL"));
	}

	@Test
	public void diagnosisBootstrapper_guardsDanglingEncounterFk() {
		// The diagnosis type FAILED on the demo with FetchNotFoundException for a missing Encounter;
		// it declares the encounter association as an extra orphan guard so a dump-orphaned encounter
		// no longer fails the whole type. Diagnosis.encounter is @ManyToOne(optional=false), eager.
		assertArrayEquals(new String[] { "e.encounter.uuid" },
		        new DiagnosisBootstrapper(new DiagnosisRecordSerializer(), null).additionalNonNullExprs());
	}

	@Test
	public void orderBootstrappers_guardDanglingEncounterFk() {
		// Order.encounter is not-null + eager (Order.hbm.xml), inherited by all three order subtypes,
		// so they share diagnosis's exact latent failure: a dump-orphaned order-encounter would fail
		// the whole type. They didn't trip on the current demo dump only because it had no such orphan.
		assertArrayEquals(new String[] { "e.encounter.uuid" },
		        new DrugOrderBootstrapper(new DrugOrderRecordSerializer(), null).additionalNonNullExprs());
		assertArrayEquals(new String[] { "e.encounter.uuid" },
		        new TestOrderBootstrapper(new TestOrderRecordSerializer(), null).additionalNonNullExprs());
		assertArrayEquals(new String[] { "e.encounter.uuid" },
		        new ReferralOrderBootstrapper(new ReferralOrderRecordSerializer(), null).additionalNonNullExprs());
	}

	@Test
	public void types_with_nullable_or_no_encounter_declare_no_additionalNonNullExprs() {
		// The guard is IS-NOT-NULL (drops only orphans of a MANDATORY association). It must NOT be
		// applied where encounter is nullable, or it would drop legitimate null-encounter records:
		// Obs.encounter (Obs.hbm.xml, no not-null), Condition.encounter (optional=true). Encounter is
		// itself the entity (no encounter association). All rely on the patient/person guard alone.
		assertEquals(0, new EncounterBootstrapper(new EncounterRecordSerializer(), null).additionalNonNullExprs().length);
		assertEquals(0, new ObsBootstrapper(new ObsRecordSerializer(), null).additionalNonNullExprs().length);
		assertEquals(0, new ConditionBootstrapper(new ConditionRecordSerializer(), null).additionalNonNullExprs().length);
	}

	@Test
	public void hibernateBootstrapper_countHql_mirrorsTheScanWhere() {
		// Drift's "expected" count must use the same WHERE the scan walks, so it counts exactly what
		// would be indexed: SELECT count(e), voided filter, patient guard, extra orphan guards, no order.
		String hql = HibernateTypeBootstrapper.countHql("Encounter", "e.patient.uuid");
		assertTrue("counts, not pages", hql.startsWith("SELECT count(e) FROM Encounter e"));
		assertTrue("voided filter present", hql.contains("WHERE e.voided = false"));
		assertTrue("patient guard present", hql.contains("AND e.patient.uuid IS NOT NULL"));

		String diag = HibernateTypeBootstrapper.countHql("Diagnosis", "e.patient.uuid", "e.encounter.uuid");
		assertTrue("extra mandatory-eager guard counted too", diag.contains("AND e.encounter.uuid IS NOT NULL"));
	}

	@Test
	public void hibernateBootstrapper_afterCursorHql_handlesTieBreaker() {
		String hql = HibernateTypeBootstrapper.afterCursorHql("Encounter",
		        "COALESCE(e.dateChanged, e.dateCreated)", "e.patient.uuid");
		assertTrue("strictly-greater cursor branch present",
		        hql.contains("COALESCE(e.dateChanged, e.dateCreated) > :cursor"));
		assertTrue("equal-cursor uuid tie-breaker present",
		        hql.contains("COALESCE(e.dateChanged, e.dateCreated) = :cursor AND e.uuid > :afterUuid"));
	}

	@Test
	public void hibernateBootstrapper_firstPagePerPatientHql_shape() {
		String hql = HibernateTypeBootstrapper.firstPagePerPatientHql("Encounter",
		        "COALESCE(e.dateChanged, e.dateCreated)", "e.patient.uuid");
		assertTrue("entity name present", hql.contains("FROM Encounter e"));
		assertTrue("voided filter present", hql.contains("WHERE e.voided = false"));
		assertTrue("patient association filter present", hql.contains("AND e.patient.uuid = :patientUuid"));
		assertTrue("order clause present",
		        hql.contains("ORDER BY COALESCE(e.dateChanged, e.dateCreated) ASC, e.uuid ASC"));
	}

	@Test
	public void hibernateBootstrapper_afterCursorPerPatientHql_shape() {
		String hql = HibernateTypeBootstrapper.afterCursorPerPatientHql("Encounter",
		        "COALESCE(e.dateChanged, e.dateCreated)", "e.patient.uuid");
		assertTrue("patient filter present", hql.contains("AND e.patient.uuid = :patientUuid"));
		assertTrue("strictly-greater cursor branch present",
		        hql.contains("COALESCE(e.dateChanged, e.dateCreated) > :cursor"));
		assertTrue("equal-cursor uuid tie-breaker present",
		        hql.contains("COALESCE(e.dateChanged, e.dateCreated) = :cursor AND e.uuid > :afterUuid"));
	}

	@Test
	public void obsBootstrapper_overrides_patientAssociationExpr_to_person() {
		// Obs has no patient association — Hibernate would throw on e.patient.uuid. The Person/Patient
		// UUID coincidence is what makes filtering by e.person.uuid resolve correctly.
		assertEquals("e.person.uuid",
		        new ObsBootstrapper(new ObsRecordSerializer(), null).patientAssociationExpr());
	}

	@Test
	public void patientBootstrapper_overrides_patientAssociationExpr_to_uuid() {
		// Patient is the entity itself; filter by uuid, not by a patient association.
		assertEquals("e.uuid",
		        new PatientBootstrapper(new PatientRecordSerializer(), null).patientAssociationExpr());
	}

	@Test
	public void other_bootstrappers_keep_default_patientAssociationExpr() {
		// Spot-check three of the ten "has-a-patient" types — defaults inherited from
		// HibernateTypeBootstrapper resolve to e.patient.uuid as expected.
		String defaultExpr = "e.patient.uuid";
		assertEquals(defaultExpr,
		        new EncounterBootstrapper(new EncounterRecordSerializer(), null).patientAssociationExpr());
		assertEquals(defaultExpr,
		        new ConditionBootstrapper(new ConditionRecordSerializer(), null).patientAssociationExpr());
		assertEquals(defaultExpr,
		        new DrugOrderBootstrapper(new DrugOrderRecordSerializer(), null).patientAssociationExpr());
	}

	@Test
	public void obs_and_order_subtypes_override_cursorDateExpr_to_dateCreated() {
		// Obs.hbm.xml and Order.hbm.xml don't map dateChanged; the HQL must use dateCreated alone
		// for these 4 bootstrappers or Hibernate throws QueryException at first fetch.
		assertEquals("e.dateCreated", new ObsBootstrapper(new ObsRecordSerializer(), null).cursorDateExpr());
		assertEquals("e.dateCreated", new DrugOrderBootstrapper(new DrugOrderRecordSerializer(), null).cursorDateExpr());
		assertEquals("e.dateCreated", new TestOrderBootstrapper(new TestOrderRecordSerializer(), null).cursorDateExpr());
		assertEquals("e.dateCreated", new ReferralOrderBootstrapper(new ReferralOrderRecordSerializer(), null).cursorDateExpr());
	}

	@Test
	public void jpa_mapped_types_keep_default_cursor_expr() {
		// JPA-annotated entities inherit dateChanged via BaseOpenmrsData's @MappedSuperclass; the
		// default COALESCE expression is what they need. Spot-check Encounter and PatientProgram
		// (the latter has an explicit hbm.xml mapping that includes dateChanged).
		String defaultExpr = "COALESCE(e.dateChanged, e.dateCreated)";
		assertEquals(defaultExpr, new EncounterBootstrapper(new EncounterRecordSerializer(), null).cursorDateExpr());
		assertEquals(defaultExpr,
		        new PatientProgramBootstrapper(new PatientProgramRecordSerializer(), null).cursorDateExpr());
	}

	@Test
	public void all_twelve_bootstrappers_distinct_resourceTypes() {
		// Same-name registration would make BootstrapServiceImpl.setBootstrappers silently shadow
		// the earlier-registered bootstrapper.
		long distinct = specs().stream().map(s -> s.bootstrapper.getResourceType()).distinct().count();
		assertEquals(12, distinct);
	}

	@Test
	public void all_twelve_bootstrappers_distinct_entityClasses() {
		long distinct = specs().stream().map(s -> s.bootstrapper.getSerializer().getSupportedType()).distinct().count();
		assertEquals(12, distinct);
	}

	@Test
	public void registrationListMatchesSpecCount() {
		// Sanity guard against forgetting to add a new bootstrapper here when adding a 13th type.
		assertNotNull(specs());
		assertEquals(12, specs().size());
	}

	private static List<BootstrapperSpec> specs() {
		// Each spec carries the concrete serializer (so the bootstrapper has its real dependency)
		// plus the expected resource_type and entity class. DbSessionFactory is null because no
		// fetchPage call is made in these tests.
		ObsRecordSerializer obs = new ObsRecordSerializer();
		EncounterRecordSerializer enc = new EncounterRecordSerializer();
		VisitRecordSerializer visit = new VisitRecordSerializer();
		PatientRecordSerializer patient = new PatientRecordSerializer();
		ConditionRecordSerializer cond = new ConditionRecordSerializer();
		DiagnosisRecordSerializer diag = new DiagnosisRecordSerializer();
		AllergyRecordSerializer allergy = new AllergyRecordSerializer();
		DrugOrderRecordSerializer drug = new DrugOrderRecordSerializer();
		TestOrderRecordSerializer test = new TestOrderRecordSerializer();
		ReferralOrderRecordSerializer ref = new ReferralOrderRecordSerializer();
		MedicationDispenseRecordSerializer disp = new MedicationDispenseRecordSerializer();
		PatientProgramRecordSerializer pp = new PatientProgramRecordSerializer();

		return Arrays.asList(
		        new BootstrapperSpec(new ObsBootstrapper(obs, null), obs, "obs", Obs.class),
		        new BootstrapperSpec(new EncounterBootstrapper(enc, null), enc, "encounter", Encounter.class),
		        new BootstrapperSpec(new VisitBootstrapper(visit, null), visit, "visit", Visit.class),
		        new BootstrapperSpec(new PatientBootstrapper(patient, null), patient, "patient", Patient.class),
		        new BootstrapperSpec(new ConditionBootstrapper(cond, null), cond, "condition", Condition.class),
		        new BootstrapperSpec(new DiagnosisBootstrapper(diag, null), diag, "diagnosis", Diagnosis.class),
		        new BootstrapperSpec(new AllergyBootstrapper(allergy, null), allergy, "allergy", Allergy.class),
		        new BootstrapperSpec(new DrugOrderBootstrapper(drug, null), drug, "drug_order", DrugOrder.class),
		        new BootstrapperSpec(new TestOrderBootstrapper(test, null), test, "test_order", TestOrder.class),
		        new BootstrapperSpec(new ReferralOrderBootstrapper(ref, null), ref, "referral_order", ReferralOrder.class),
		        new BootstrapperSpec(new MedicationDispenseBootstrapper(disp, null), disp, "medication_dispense", MedicationDispense.class),
		        new BootstrapperSpec(new PatientProgramBootstrapper(pp, null), pp, "program", PatientProgram.class));
	}

	private static final class BootstrapperSpec {
		final TypeBootstrapper<?> bootstrapper;
		final Object serializer;
		final String expectedResourceType;
		final Class<?> expectedEntityClass;

		BootstrapperSpec(TypeBootstrapper<?> b, Object s, String type, Class<?> cls) {
			this.bootstrapper = b;
			this.serializer = s;
			this.expectedResourceType = type;
			this.expectedEntityClass = cls;
		}
	}
}
