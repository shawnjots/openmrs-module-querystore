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
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_DATE_STOPPED;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_ORDER_NUMBER;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_PREVIOUS_ORDER_UUID;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_PROVIDER_NAME;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_PROVIDER_UUID;
import static org.openmrs.module.querystore.QueryStoreConstants.FIELD_URGENCY;

import java.time.LocalDate;

import org.openmrs.CareSetting;
import org.openmrs.Order;
import org.openmrs.module.querystore.model.QueryDocument;
import org.openmrs.module.querystore.util.DateFormatUtil;

/**
 * Shared {@link Order}-family serialization for the per-subtype order indices. Hosts the
 * Order-base cross-cutting fields (action, urgency, care setting, previous order, order number,
 * date stopped, auto-expire date), the Order-identity overrides (patient_uuid from {@code
 * Order.getPatient()}, resource_uuid from {@code Order.getUuid()}, document date from
 * {@code Order.getDateActivated()}), and the "orderer overrides encounter provider" convention
 * documented in [Decision 6 / Order family]. Order-subtype-specific fields (dosing instructions,
 * asNeeded, route, frequency, drug for DrugOrder; specimen source, laterality, clinical history,
 * instructions for ServiceOrder) live in subclasses.
 */
public abstract class AbstractOrderRecordSerializer<T extends Order> extends AbstractRecordSerializer<T> {

	@Override
	protected String getPatientUuid(T order) {
		return order.getPatient() != null ? order.getPatient().getUuid() : null;
	}

	@Override
	protected String getResourceUuid(T order) {
		return order.getUuid();
	}

	@Override
	protected LocalDate getDate(T order) {
		return DateFormatUtil.toLocalDate(order.getDateActivated());
	}

	/**
	 * Writes the Order-base metadata fields shared across every order subtype: action, urgency,
	 * care setting (name-only — Decision 9 exception per the existing field-descriptions),
	 * previous order UUID, order number, date stopped, auto-expire date. The two date strings are
	 * passed in already-formatted because subclasses also need them for text composition; this
	 * keeps the formatter call once per record per the single-walk contract.
	 */
	protected static void putOrderBaseFields(QueryDocument doc, Order order,
	                                        String dateStoppedText, String autoExpireText) {
		if (order.getAction() != null) {
			doc.putMetadata(FIELD_ACTION, order.getAction().name());
		}
		if (order.getUrgency() != null) {
			doc.putMetadata(FIELD_URGENCY, order.getUrgency().name());
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
	}

	/**
	 * Writes encounter context and applies the "orderer overrides encounter provider" Order-family
	 * convention: {@code Order.getOrderer()} is the authoritative {@code provider_uuid} /
	 * {@code provider_name} source when non-null, overriding the encounter's first active provider
	 * that {@code putEncounterContext} otherwise picks. Subclasses call this once at the end of
	 * {@code populate} to keep the convention encoded in one place.
	 */
	protected final void putOrderEncounterAndProvider(QueryDocument doc, Order order) {
		putEncounterContext(doc, order.getEncounter());
		putUuidAndName(doc, FIELD_PROVIDER_UUID, FIELD_PROVIDER_NAME, order.getOrderer());
	}
}
