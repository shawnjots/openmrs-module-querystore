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

import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_ACTION;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_AUTO_EXPIRE_DATE;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_CARE_SETTING;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_CLINICAL_HISTORY;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_DATE_STOPPED;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_INSTRUCTIONS;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_LATERALITY;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_ORDER_NUMBER;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_PREVIOUS_ORDER_UUID;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_PROVIDER_NAME;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_PROVIDER_UUID;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_SPECIMEN_SOURCE_NAME;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_SPECIMEN_SOURCE_UUID;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_URGENCY;

import java.time.LocalDate;
import java.util.Date;

import org.openmrs.CareSetting;
import org.openmrs.Concept;
import org.openmrs.Order;
import org.openmrs.Provider;
import org.openmrs.ServiceOrder;
import org.openmrs.TestOrder;
import org.openmrs.module.querystore.model.QueryDocument;
import org.openmrs.module.querystore.util.ConceptNameUtil;
import org.openmrs.module.querystore.util.DateFormatUtil;

/**
 * Serializes a {@link TestOrder} into a {@link QueryDocument} for the {@code openmrs_test_order}
 * index. Last of the Order family — mirrors {@link DrugOrderRecordSerializer} for the shared
 * Order-base surface (action, urgency, care setting, previous order, order number, date stopped,
 * auto-expire date, orderer-overrides-encounter-provider). Test-order-specific fields come from
 * the {@link ServiceOrder} subclass: laterality (LEFT/RIGHT/BILATERAL), specimen source (Concept,
 * stored as UUID+name per Decision 9), and clinical history (free text, included in embedded
 * text per the ADR example). The display name is the order's concept's preferred name — there
 * is no Drug analogue here.
 */
public class TestOrderRecordSerializer extends AbstractRecordSerializer<TestOrder> {

	@Override
	public String getResourceType() {
		return "test_order";
	}

	@Override
	public Class<TestOrder> getSupportedType() {
		return TestOrder.class;
	}

	@Override
	protected String getPatientUuid(TestOrder order) {
		return order.getPatient() != null ? order.getPatient().getUuid() : null;
	}

	@Override
	protected String getResourceUuid(TestOrder order) {
		return order.getUuid();
	}

	@Override
	protected LocalDate getDate(TestOrder order) {
		return DateFormatUtil.toLocalDate(order.getDateActivated());
	}

	@Override
	protected void populate(TestOrder order, QueryDocument doc) {
		Concept concept = order.getConcept();
		String preferredName = ConceptNameUtil.getPreferredName(concept);
		if (preferredName.isEmpty()) {
			return;
		}

		Concept specimenSource = order.getSpecimenSource();
		String specimenSourceName = conceptName(specimenSource);
		String clinicalHistory = trimToNull(order.getClinicalHistory());
		String instructions = trimToNull(order.getInstructions());
		ServiceOrder.Laterality laterality = order.getLaterality();
		Order.Action action = order.getAction();
		Order.Urgency urgency = order.getUrgency();
		Date dateStopped = order.getDateStopped();
		String dateStoppedText = dateStopped != null ? DateFormatUtil.formatDate(dateStopped) : null;
		Date autoExpireDate = order.getAutoExpireDate();
		String autoExpireText = autoExpireDate != null ? DateFormatUtil.formatDate(autoExpireDate) : null;

		doc.setText(buildText(preferredName, laterality, action, urgency, clinicalHistory,
		        instructions, dateStoppedText));

		putConceptFields(doc, concept, preferredName);

		if (laterality != null) {
			doc.putMetadata(FIELD_LATERALITY, laterality.name());
		}
		putConceptUuidAndName(doc, FIELD_SPECIMEN_SOURCE_UUID, FIELD_SPECIMEN_SOURCE_NAME,
		        specimenSource, specimenSourceName);
		if (clinicalHistory != null) {
			doc.putMetadata(FIELD_CLINICAL_HISTORY, clinicalHistory);
		}
		if (instructions != null) {
			doc.putMetadata(FIELD_INSTRUCTIONS, instructions);
		}
		if (action != null) {
			doc.putMetadata(FIELD_ACTION, action.name());
		}
		if (urgency != null) {
			doc.putMetadata(FIELD_URGENCY, urgency.name());
		}
		CareSetting careSetting = order.getCareSetting();
		if (careSetting != null && careSetting.getName() != null) {
			doc.putMetadata(FIELD_CARE_SETTING, careSetting.getName());
		}
		Order previous = order.getPreviousOrder();
		if (previous != null) {
			doc.putMetadata(FIELD_PREVIOUS_ORDER_UUID, previous.getUuid());
		}
		if (order.getOrderNumber() != null) {
			doc.putMetadata(FIELD_ORDER_NUMBER, order.getOrderNumber());
		}
		if (dateStoppedText != null) {
			doc.putMetadata(FIELD_DATE_STOPPED, dateStoppedText);
		}
		if (autoExpireText != null) {
			doc.putMetadata(FIELD_AUTO_EXPIRE_DATE, autoExpireText);
		}

		putEncounterContext(doc, order.getEncounter());
		putOrderer(doc, order.getOrderer());
	}

	private static String buildText(String preferredName, ServiceOrder.Laterality laterality,
	                                Order.Action action, Order.Urgency urgency,
	                                String clinicalHistory, String instructions,
	                                String dateStoppedText) {
		StringBuilder sb = new StringBuilder("Test order: ").append(preferredName);

		if (laterality != null) {
			sb.append(". Laterality: ").append(laterality.name());
		}
		if (clinicalHistory != null) {
			sb.append(". Clinical history: ").append(clinicalHistory);
		}
		if (action != null) {
			sb.append(". Action: ").append(action.name());
		}
		if (urgency != null) {
			sb.append(". Urgency: ").append(urgency.name());
		}
		if (dateStoppedText != null) {
			sb.append(". Stopped: ").append(dateStoppedText);
		}
		if (instructions != null) {
			sb.append(". ").append(instructions);
		}
		return sb.toString();
	}

	private static void putConceptUuidAndName(QueryDocument doc, String uuidKey, String nameKey,
	                                          Concept concept, String resolvedName) {
		if (concept == null) {
			return;
		}
		doc.putMetadata(uuidKey, concept.getUuid());
		if (resolvedName != null) {
			doc.putMetadata(nameKey, resolvedName);
		}
	}

	private static void putOrderer(QueryDocument doc, Provider orderer) {
		if (orderer == null) {
			return;
		}
		doc.putMetadata(FIELD_PROVIDER_UUID, orderer.getUuid());
		if (orderer.getName() != null) {
			doc.putMetadata(FIELD_PROVIDER_NAME, orderer.getName());
		}
	}

	private static String conceptName(Concept concept) {
		if (concept == null) {
			return null;
		}
		String name = ConceptNameUtil.getPreferredName(concept);
		return name.isEmpty() ? null : name;
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}
}
