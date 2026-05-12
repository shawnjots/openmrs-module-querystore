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

import org.openmrs.module.querystore.bootstrap.TypeBootstrapper;
import org.openmrs.module.querystore.serialization.ClinicalRecordSerializer;

/**
 * SPI for modules that provide custom resource types to the query store (ADR Decision 13).
 *
 * <p>A providing module — appointments, billing, radiology — packages a serializer producing the
 * cross-cutting document contract (ADR Decision 6) and an optional bootstrapper for initial backfill,
 * then declares the resource type name. Querystore discovers provider beans via
 * {@code Context.getRegisteredComponents(ResourceTypeProvider.class)} so a providing module only
 * needs to register its bean in its own {@code moduleApplicationContext.xml}; querystore does not
 * need to know about specific modules.
 *
 * <p><b>Indexing trigger lives in the providing module.</b> Subclass
 * {@link org.openmrs.module.querystore.bridge.AbstractIndexingAdvice} and wire it as AOP advice on
 * the module's own service, mirroring how core-type advice is wired in querystore. The
 * {@code querystore.bridge.indexer} and {@code querystore.bridge.dispatcher} beans are reachable
 * via {@code Context.getRegisteredComponent(...)} so providers reuse the embed-then-upsert
 * after-commit pipeline without re-implementing it. AOP is a time-bound migration bridge per ADR
 * Decision 12; when events-first sync lands, this SPI will grow an event-subscription hook and the
 * AOP path retires alongside the core-type advice.
 *
 * <p><b>Name rule.</b> {@link #getResourceType()} must be {@code <moduleid>_<type>} per ADR
 * Decision 13 — e.g., {@code appointments_appointment}, {@code billing_bill}. Unprefixed names are
 * reserved for the types querystore itself indexes from core; querystore throws at startup if a
 * provider violates the rule.
 *
 * <p><b>Backends self-heal on first write.</b> All three reference backends (MySQL, Lucene,
 * Elasticsearch) call {@code ensureSchema} lazily on the first upsert per resource type. A
 * provider does not declare a {@link org.openmrs.module.querystore.backend.SchemaSpec} —
 * Decision 3 fixes the structured-field column as {@code metadata_json}, so providing modules
 * have no DDL knob to turn at v1.
 */
public interface ResourceTypeProvider {

	/** The provider's resource type name. Must match {@code <moduleid>_<type>}. */
	String getResourceType();

	/** Serializer producing the {@link org.openmrs.module.querystore.model.QueryDocument} for this
	 *  provider's records. Must populate the cross-cutting fields contract from ADR Decision 6
	 *  ({@code patient_uuid}, {@code resource_type}, {@code resource_uuid}, {@code last_modified},
	 *  {@code text}, encounter/visit/location/provider where applicable). */
	ClinicalRecordSerializer<?> getSerializer();

	/**
	 * Bootstrapper for initial backfill. May be {@code null} when the provider has no historical
	 * records to project (e.g., a type whose first record post-dates module install). When present,
	 * querystore appends it to its sequential bootstrap order — after all core types so a long-
	 * running core obs scan does not delay smaller provided types from being backfilled.
	 */
	TypeBootstrapper<?> getBootstrapper();
}
