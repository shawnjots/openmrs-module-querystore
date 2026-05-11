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

import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.hibernate.Session;
import org.hibernate.query.Query;
import org.openmrs.Obs;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.querystore.serialization.AbstractRecordSerializer;
import org.openmrs.module.querystore.serialization.ClinicalRecordSerializer;
import org.openmrs.module.querystore.serialization.ObsRecordSerializer;

/**
 * Walks core's {@code obs} table via HQL ordered by effective {@code dateChanged ?? dateCreated}
 * ascending. Voided obs are filtered at fetch time per {@link Obs#getVoided()} — Decision 10
 * deletes voided records from the read store rather than projecting them with a flag.
 */
public class ObsBootstrapper extends TypeBootstrapper<Obs> {

	private static final String HQL_FIRST_PAGE = "FROM Obs o WHERE o.voided = false "
	        + "ORDER BY COALESCE(o.dateChanged, o.dateCreated) ASC, o.uuid ASC";

	// COALESCE in WHERE so a record whose dateChanged is null still progresses past the cursor on
	// dateCreated alone; the uuid tie-breaker handles records sharing the same effective timestamp.
	private static final String HQL_AFTER_CURSOR = "FROM Obs o WHERE o.voided = false AND ("
	        + "COALESCE(o.dateChanged, o.dateCreated) > :cursor "
	        + "OR (COALESCE(o.dateChanged, o.dateCreated) = :cursor AND o.uuid > :afterUuid)) "
	        + "ORDER BY COALESCE(o.dateChanged, o.dateCreated) ASC, o.uuid ASC";

	private final ObsRecordSerializer serializer;

	private final DbSessionFactory sessionFactory;

	public ObsBootstrapper(ObsRecordSerializer serializer, DbSessionFactory sessionFactory) {
		this.serializer = serializer;
		this.sessionFactory = sessionFactory;
	}

	@Override
	public String getResourceType() {
		return "obs";
	}

	@Override
	protected ClinicalRecordSerializer<Obs> getSerializer() {
		return serializer;
	}

	@Override
	protected Instant getDateChanged(Obs obs) {
		// EPOCH (not null) when both audit dates are unset so the cursor stays monotonic and a
		// subsequent fetchPage doesn't fall back to HQL_FIRST_PAGE and re-scan from the beginning.
		Instant i = AbstractRecordSerializer.lastModifiedOf(obs);
		return i != null ? i : Instant.EPOCH;
	}

	@Override
	protected List<Obs> fetchPage(Instant afterDateChanged, String afterUuid, int pageSize) {
		// Use the modern parameterized Query API; DbSession.createQuery returns the legacy raw type.
		Session session = sessionFactory.getHibernateSessionFactory().getCurrentSession();
		Query<Obs> q;
		if (afterDateChanged == null) {
			q = session.createQuery(HQL_FIRST_PAGE, Obs.class);
		} else {
			q = session.createQuery(HQL_AFTER_CURSOR, Obs.class);
			q.setParameter("cursor", Date.from(afterDateChanged));
			q.setParameter("afterUuid", afterUuid != null ? afterUuid : "");
		}
		q.setMaxResults(pageSize);
		return q.list();
	}
}
