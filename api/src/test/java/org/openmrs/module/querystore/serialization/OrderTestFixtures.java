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

import java.lang.reflect.Field;

import org.openmrs.Order;

/**
 * Reflection-based helpers for setting {@link Order} fields whose values core only assigns through
 * {@code OrderService} when an order is saved or discontinued (e.g. {@code orderNumber},
 * {@code dateStopped}). Tests need to drive these states without going through the service layer.
 * Static-import from order-family test classes.
 */
final class OrderTestFixtures {

	private OrderTestFixtures() {
	}

	static void setOrderField(Order order, String fieldName, Object value) {
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
