/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.serialization;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.openmrs.module.querystore.serialization.ConceptFixtures.concept;
import static org.openmrs.module.querystore.serialization.DateFixtures.utcDate;
import static org.openmrs.module.querystore.serialization.EncounterFixtures.encounterWithProvider;
import static org.openmrs.module.querystore.serialization.OrderTestFixtures.setOrderField;
import static org.openmrs.module.querystore.serialization.ProviderFixtures.providerNamed;

import java.util.Calendar;
import java.util.Date;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.CareSetting;
import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.EncounterType;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.Provider;
import org.openmrs.ServiceOrder;
import org.openmrs.TestOrder;
import org.openmrs.module.querystore.model.QueryDocument;

public class TestOrderRecordSerializerTest {

	private TestOrderRecordSerializer serializer;

	@Before
	public void setUp() {
		serializer = new TestOrderRecordSerializer();
	}

	@Test
	public void serialize_fullTestOrder_matchesAdrTextShape() {
		TestOrder order = order(concept("X-Ray Chest"));
		order.setLaterality(ServiceOrder.Laterality.LEFT);
		order.setClinicalHistory("Persistent cough for 3 weeks");
		order.setAction(Order.Action.NEW);
		order.setUrgency(Order.Urgency.STAT);

		QueryDocument doc = serializer.serialize(order);

		assertEquals("test_order", doc.getResourceType());
		assertEquals(
				"Test order: X-Ray Chest. Laterality: LEFT."
				        + " Clinical history: Persistent cough for 3 weeks. Action: NEW. Urgency: STAT",
				doc.getText());
	}

	@Test
	public void serialize_conceptField_populatedFromConcept() {
		Concept concept = concept("X-Ray Chest");
		concept.setUuid("concept-uuid");

		TestOrder order = order(concept);

		QueryDocument doc = serializer.serialize(order);

		assertEquals("concept-uuid", doc.getMetadata().get("concept_uuid"));
		assertEquals("X-Ray Chest", doc.getMetadata().get("concept_name"));
	}

	@Test
	public void serialize_laterality_useEnumName() {
		TestOrder order = order(concept("X-Ray Knee"));
		order.setLaterality(ServiceOrder.Laterality.BILATERAL);

		QueryDocument doc = serializer.serialize(order);

		assertEquals("BILATERAL", doc.getMetadata().get("laterality"));
		assertTrue(doc.getText().contains("Laterality: BILATERAL"));
	}

	@Test
	public void serialize_specimenSource_populatesUuidAndName() {
		Concept specimenSource = concept("Blood");
		specimenSource.setUuid("specimen-uuid");

		TestOrder order = order(concept("Hemoglobin"));
		order.setSpecimenSource(specimenSource);

		QueryDocument doc = serializer.serialize(order);

		assertEquals("specimen-uuid", doc.getMetadata().get("specimen_source_uuid"));
		assertEquals("Blood", doc.getMetadata().get("specimen_source_name"));
	}

	@Test
	public void serialize_clinicalHistory_appearsInTextAndMetadata() {
		TestOrder order = order(concept("CT Brain"));
		order.setClinicalHistory("Acute headache, rule out bleed");

		QueryDocument doc = serializer.serialize(order);

		assertEquals("Acute headache, rule out bleed", doc.getMetadata().get("clinical_history"));
		assertTrue(doc.getText().contains("Clinical history: Acute headache, rule out bleed"));
	}

	@Test
	public void serialize_instructions_appearsInTextAndMetadata() {
		TestOrder order = order(concept("Glucose"));
		order.setInstructions("Fasting required");

		QueryDocument doc = serializer.serialize(order);

		assertEquals("Fasting required", doc.getMetadata().get("instructions"));
		assertTrue(doc.getText().endsWith("Fasting required"));
	}

	@Test
	public void serialize_actionAndUrgency_useEnumName() {
		TestOrder order = order(concept("X-Ray Chest"));
		order.setAction(Order.Action.REVISE);
		order.setUrgency(Order.Urgency.ROUTINE);

		QueryDocument doc = serializer.serialize(order);

		assertEquals("REVISE", doc.getMetadata().get("action"));
		assertEquals("ROUTINE", doc.getMetadata().get("urgency"));
	}

	@Test
	public void serialize_careSetting_usesName() {
		CareSetting outpatient = new CareSetting();
		outpatient.setName("Outpatient");

		TestOrder order = order(concept("CBC"));
		order.setCareSetting(outpatient);

		QueryDocument doc = serializer.serialize(order);

		assertEquals("Outpatient", doc.getMetadata().get("care_setting"));
	}

	@Test
	public void serialize_previousOrder_populatesUuid() {
		TestOrder previous = new TestOrder();
		previous.setUuid("previous-order-uuid");

		TestOrder order = order(concept("CBC"));
		order.setPreviousOrder(previous);

		QueryDocument doc = serializer.serialize(order);

		assertEquals("previous-order-uuid", doc.getMetadata().get("previous_order_uuid"));
	}

	@Test
	public void serialize_orderNumber_populatedViaReflection() {
		TestOrder order = order(concept("X-Ray Chest"));
		setOrderField(order, "orderNumber", "ORD-5678");

		QueryDocument doc = serializer.serialize(order);

		assertEquals("ORD-5678", doc.getMetadata().get("order_number"));
	}

	@Test
	public void serialize_dateStopped_appearsInTextAndMetadata() {
		TestOrder order = order(concept("X-Ray Chest"));
		setOrderField(order, "dateStopped", utcDate(2025, Calendar.JULY, 1));

		QueryDocument doc = serializer.serialize(order);

		assertTrue(doc.getText().contains("Stopped: 2025-07-01"));
		assertEquals("2025-07-01", doc.getMetadata().get("date_stopped"));
	}

	@Test
	public void serialize_autoExpireDate_inMetadataNotText() {
		TestOrder order = order(concept("X-Ray Chest"));
		order.setAutoExpireDate(utcDate(2025, Calendar.AUGUST, 1));

		QueryDocument doc = serializer.serialize(order);

		assertEquals("2025-08-01", doc.getMetadata().get("auto_expire_date"));
		assertFalse("auto_expire_date is metadata-only", doc.getText().contains("2025-08-01"));
	}

	@Test
	public void serialize_orderer_overridesEncounterProvider() {
		Provider encounterProvider = providerNamed("encounter-provider-uuid", "Nurse", "Akinyi");
		Encounter enc = encounterWithProvider("enc-uuid", encounterProvider);
		Provider orderer = providerNamed("orderer-uuid", "Dr.", "Ochieng");

		TestOrder order = order(concept("X-Ray Chest"));
		order.setEncounter(enc);
		order.setOrderer(orderer);

		QueryDocument doc = serializer.serialize(order);

		assertEquals("orderer-uuid", doc.getMetadata().get("provider_uuid"));
		assertEquals("Dr. Ochieng", doc.getMetadata().get("provider_name"));
	}

	@Test
	public void serialize_nullOrderer_preservesEncounterProvider() {
		Provider encounterProvider = providerNamed("encounter-provider-uuid", "Dr.", "Ochieng");
		Encounter enc = encounterWithProvider("enc-uuid", encounterProvider);

		TestOrder order = order(concept("X-Ray Chest"));
		order.setEncounter(enc);

		QueryDocument doc = serializer.serialize(order);

		assertEquals("encounter-provider-uuid", doc.getMetadata().get("provider_uuid"));
		assertEquals("Dr. Ochieng", doc.getMetadata().get("provider_name"));
	}

	@Test
	public void serialize_encounterContext_populated() {
		EncounterType type = new EncounterType();
		type.setUuid("etype-uuid");
		type.setName("Adult Outpatient Visit");
		Encounter enc = new Encounter();
		enc.setUuid("enc-uuid");
		enc.setEncounterType(type);

		TestOrder order = order(concept("X-Ray Chest"));
		order.setEncounter(enc);

		QueryDocument doc = serializer.serialize(order);

		assertEquals("enc-uuid", doc.getMetadata().get("encounter_uuid"));
		assertEquals("etype-uuid", doc.getMetadata().get("encounter_type_uuid"));
		assertEquals("Adult Outpatient Visit", doc.getMetadata().get("encounter_type_name"));
	}

	@Test
	public void serialize_emptyOrder_returnsNull() {
		TestOrder order = new TestOrder();
		assertNull(serializer.serialize(order));
	}

	@Test
	public void serialize_carriesPatientAndResourceUuids() {
		Patient patient = new Patient();
		patient.setUuid("patient-uuid");

		TestOrder order = order(concept("X-Ray Chest"));
		order.setUuid("order-uuid");
		order.setPatient(patient);

		QueryDocument doc = serializer.serialize(order);

		assertEquals("patient-uuid", doc.getPatientUuid());
		assertEquals("order-uuid", doc.getResourceUuid());
	}

	private static TestOrder order(Concept concept) {
		TestOrder order = new TestOrder();
		order.setConcept(concept);
		order.setDateActivated(new Date());
		return order;
	}

}
