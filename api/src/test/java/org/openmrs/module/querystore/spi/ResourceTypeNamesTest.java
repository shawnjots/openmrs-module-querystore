/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.spi;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class ResourceTypeNamesTest {

	@Test
	public void validateProvided_acceptsSimpleModuleidTypeName() {
		ResourceTypeNames.validateProvided("appointments_appointment");
		ResourceTypeNames.validateProvided("billing_bill");
	}

	@Test
	public void validateProvided_acceptsExtraUnderscoresInTypeSegment() {
		ResourceTypeNames.validateProvided("billing_payment_method");
		ResourceTypeNames.validateProvided("radiology_imaging_study_result");
	}

	@Test
	public void validateProvided_acceptsDigitsAfterFirstChar() {
		ResourceTypeNames.validateProvided("fhir2_observation");
	}

	@Test
	public void validateProvided_rejectsNull() {
		assertRejected(null);
	}

	@Test
	public void validateProvided_rejectsUnprefixed() {
		// no underscore at all — looks like a core-type name even if not currently reserved
		assertRejected("appointments");
	}

	@Test
	public void validateProvided_rejectsCoreReservedNames() {
		for (String reserved : ResourceTypeNames.CORE_RESERVED) {
			assertRejected(reserved);
		}
	}

	@Test
	public void validateProvided_rejectsLeadingDigit() {
		assertRejected("1appt_thing");
		assertRejected("appt_1thing");
	}

	@Test
	public void validateProvided_rejectsUppercase() {
		assertRejected("Appointments_appointment");
		assertRejected("appointments_Appointment");
	}

	@Test
	public void validateProvided_rejectsHyphen() {
		assertRejected("appointments-appointment");
	}

	@Test
	public void validateProvided_rejectsLeadingOrTrailingUnderscore() {
		assertRejected("_appointments_appointment");
		assertRejected("appointments_appointment_");
	}

	@Test
	public void validateProvided_rejectsConsecutiveUnderscores() {
		assertRejected("appointments__appointment");
		assertRejected("billing_payment__method");
	}

	private static void assertRejected(String name) {
		try {
			ResourceTypeNames.validateProvided(name);
			fail("Expected IllegalArgumentException for " + name);
		}
		catch (IllegalArgumentException expected) {
			assertTrue("message should mention the offending name: " + expected.getMessage(),
			        expected.getMessage().contains(String.valueOf(name)));
		}
	}
}
