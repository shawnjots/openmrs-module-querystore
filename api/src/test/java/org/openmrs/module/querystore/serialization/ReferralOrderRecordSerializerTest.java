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
import static org.junit.Assert.assertNull;
import static org.openmrs.module.querystore.serialization.ConceptFixtures.concept;
import static org.openmrs.module.querystore.serialization.DateFixtures.utcDate;
import static org.openmrs.module.querystore.serialization.ProviderFixtures.providerNamed;

import java.lang.reflect.Field;
import java.util.Calendar;
import java.util.Date;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.EncounterType;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.Provider;
import org.openmrs.ReferralOrder;
import org.openmrs.module.querystore.model.QueryDocument;

/**
 * Smoke coverage that the shared {@link AbstractServiceOrderRecordSerializer} populate path also
 * runs correctly under the ReferralOrder subclass, plus the two referral-specific discriminators
 * (resource_type = "referral_order", text prefix = "Referral order:"). The exhaustive
 * field-level coverage lives in {@link TestOrderRecordSerializerTest} since both subclasses share
 * the same populate logic — a defect there surfaces in both suites. Resist adding the same
 * test-shaped duplicates here; if a referral-only behavior emerges, that's the bar for a new test.
 */
public class ReferralOrderRecordSerializerTest {

	private ReferralOrderRecordSerializer serializer;

	@Before
	public void setUp() {
		serializer = new ReferralOrderRecordSerializer();
	}

	@Test
	public void serialize_typicalReferral_usesReferralOrderTextPrefix() {
		ReferralOrder order = order(concept("Cardiology Consultation"));
		order.setClinicalHistory("New onset palpitations, suspected arrhythmia");
		order.setAction(Order.Action.NEW);
		order.setUrgency(Order.Urgency.ROUTINE);

		QueryDocument doc = serializer.serialize(order);

		assertEquals("referral_order", doc.getResourceType());
		assertEquals(
				"Referral order: Cardiology Consultation."
				        + " Clinical history: New onset palpitations, suspected arrhythmia."
				        + " Action: NEW. Urgency: ROUTINE",
				doc.getText());
		assertEquals("New onset palpitations, suspected arrhythmia",
		        doc.getMetadata().get("clinical_history"));
		assertEquals("NEW", doc.getMetadata().get("action"));
		assertEquals("ROUTINE", doc.getMetadata().get("urgency"));
	}

	@Test
	public void serialize_conceptField_populatedFromConcept() {
		Concept concept = concept("Cardiology Consultation");
		concept.setUuid("concept-uuid");

		ReferralOrder order = order(concept);

		QueryDocument doc = serializer.serialize(order);

		assertEquals("concept-uuid", doc.getMetadata().get("concept_uuid"));
		assertEquals("Cardiology Consultation", doc.getMetadata().get("concept_name"));
	}

	@Test
	public void serialize_orderer_overridesEncounterProvider() {
		Provider orderer = providerNamed("orderer-uuid", "Dr.", "Ochieng");

		ReferralOrder order = order(concept("Cardiology Consultation"));
		order.setOrderer(orderer);

		QueryDocument doc = serializer.serialize(order);

		assertEquals("orderer-uuid", doc.getMetadata().get("provider_uuid"));
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

		ReferralOrder order = order(concept("Cardiology Consultation"));
		order.setEncounter(enc);

		QueryDocument doc = serializer.serialize(order);

		assertEquals("enc-uuid", doc.getMetadata().get("encounter_uuid"));
		assertEquals("etype-uuid", doc.getMetadata().get("encounter_type_uuid"));
		assertEquals("Adult Outpatient Visit", doc.getMetadata().get("encounter_type_name"));
	}

	@Test
	public void serialize_dateStopped_appearsInTextAndMetadata() {
		ReferralOrder order = order(concept("Cardiology Consultation"));
		setOrderField(order, "dateStopped", utcDate(2025, Calendar.JULY, 1));

		QueryDocument doc = serializer.serialize(order);

		assertEquals("2025-07-01", doc.getMetadata().get("date_stopped"));
	}

	@Test
	public void serialize_emptyOrder_returnsNull() {
		assertNull(serializer.serialize(new ReferralOrder()));
	}

	@Test
	public void serialize_carriesPatientAndResourceUuids() {
		Patient patient = new Patient();
		patient.setUuid("patient-uuid");

		ReferralOrder order = order(concept("Cardiology Consultation"));
		order.setUuid("order-uuid");
		order.setPatient(patient);

		QueryDocument doc = serializer.serialize(order);

		assertEquals("patient-uuid", doc.getPatientUuid());
		assertEquals("order-uuid", doc.getResourceUuid());
	}

	private static ReferralOrder order(Concept concept) {
		ReferralOrder order = new ReferralOrder();
		order.setConcept(concept);
		order.setDateActivated(new Date());
		return order;
	}

	private static void setOrderField(Order order, String fieldName, Object value) {
		try {
			Field f = Order.class.getDeclaredField(fieldName);
			f.setAccessible(true);
			f.set(order, value);
		}
		catch (ReflectiveOperationException e) {
			throw new AssertionError("Failed to set Order." + fieldName, e);
		}
	}
}
