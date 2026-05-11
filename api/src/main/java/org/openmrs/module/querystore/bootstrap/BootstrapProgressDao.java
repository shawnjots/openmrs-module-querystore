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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import javax.sql.DataSource;

/**
 * JDBC DAO for the {@code querystore_bootstrap_progress} table — the persistence layer the
 * bootstrap path uses to checkpoint its cursor after each page so an interrupted scan resumes
 * without re-projecting. Lives next to {@code MysqlBackendStore} in core's database for the same
 * reason: bookkeeping state belongs with the projection it tracks, regardless of which backend
 * tier is selected for document storage.
 */
public class BootstrapProgressDao {

	private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

	private static final String COLUMNS = "resource_type, status, cursor_date_changed, cursor_uuid, "
	        + "documents_indexed, started_at, completed_at, failure_message";

	private final DataSource dataSource;

	public BootstrapProgressDao(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	public BootstrapProgress find(String resourceType) {
		try (Connection conn = dataSource.getConnection();
		        PreparedStatement ps = conn.prepareStatement(
		            "SELECT " + COLUMNS + " FROM querystore_bootstrap_progress WHERE resource_type = ?")) {
			ps.setString(1, resourceType);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? read(rs) : null;
			}
		}
		catch (SQLException e) {
			throw new IllegalStateException("Could not read bootstrap progress for " + resourceType, e);
		}
	}

	public List<BootstrapProgress> findAll() {
		try (Connection conn = dataSource.getConnection();
		        PreparedStatement ps = conn.prepareStatement(
		            "SELECT " + COLUMNS + " FROM querystore_bootstrap_progress ORDER BY resource_type");
		        ResultSet rs = ps.executeQuery()) {
			List<BootstrapProgress> out = new ArrayList<>();
			while (rs.next()) {
				out.add(read(rs));
			}
			return out;
		}
		catch (SQLException e) {
			throw new IllegalStateException("Could not read bootstrap progress", e);
		}
	}

	public void save(BootstrapProgress progress) {
		String sql = "INSERT INTO querystore_bootstrap_progress (" + COLUMNS + ") "
		        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
		        + "ON DUPLICATE KEY UPDATE status = VALUES(status), "
		        + "cursor_date_changed = VALUES(cursor_date_changed), "
		        + "cursor_uuid = VALUES(cursor_uuid), "
		        + "documents_indexed = VALUES(documents_indexed), "
		        + "started_at = VALUES(started_at), "
		        + "completed_at = VALUES(completed_at), "
		        + "failure_message = VALUES(failure_message)";
		try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, progress.getResourceType());
			ps.setString(2, progress.getStatus().name());
			setInstant(ps, 3, progress.getCursorDateChanged());
			if (progress.getCursorUuid() != null) {
				ps.setString(4, progress.getCursorUuid());
			} else {
				ps.setNull(4, Types.CHAR);
			}
			ps.setLong(5, progress.getDocumentsIndexed());
			setInstant(ps, 6, progress.getStartedAt());
			setInstant(ps, 7, progress.getCompletedAt());
			if (progress.getFailureMessage() != null) {
				ps.setString(8, progress.getFailureMessage());
			} else {
				ps.setNull(8, Types.VARCHAR);
			}
			ps.executeUpdate();
		}
		catch (SQLException e) {
			throw new IllegalStateException("Could not save bootstrap progress for " + progress.getResourceType(), e);
		}
	}

	private static BootstrapProgress read(ResultSet rs) throws SQLException {
		BootstrapProgress p = new BootstrapProgress();
		p.setResourceType(rs.getString("resource_type"));
		p.setStatus(BootstrapStatus.valueOf(rs.getString("status")));
		p.setCursorDateChanged(readInstant(rs, "cursor_date_changed"));
		p.setCursorUuid(rs.getString("cursor_uuid"));
		p.setDocumentsIndexed(rs.getLong("documents_indexed"));
		p.setStartedAt(readInstant(rs, "started_at"));
		p.setCompletedAt(readInstant(rs, "completed_at"));
		p.setFailureMessage(rs.getString("failure_message"));
		return p;
	}

	private static void setInstant(PreparedStatement ps, int idx, java.time.Instant instant) throws SQLException {
		if (instant != null) {
			ps.setTimestamp(idx, Timestamp.from(instant), Calendar.getInstance(UTC));
		} else {
			ps.setNull(idx, Types.TIMESTAMP);
		}
	}

	private static java.time.Instant readInstant(ResultSet rs, String column) throws SQLException {
		Timestamp ts = rs.getTimestamp(column, Calendar.getInstance(UTC));
		return ts != null ? ts.toInstant() : null;
	}
}
