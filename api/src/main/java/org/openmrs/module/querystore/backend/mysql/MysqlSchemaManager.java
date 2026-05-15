/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.backend.mysql;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.querystore.QueryStoreConstants;
import org.openmrs.module.querystore.backend.JdbcSupport;

/**
 * Creates and drops per-type tables ({@code querystore_<type>}) in the OpenMRS database. Tables are
 * created lazily on first {@link #ensureTable(String)} call; existence is cached so repeated
 * upserts don't repeat DDL probes. The {@code querystore_} prefix on every table name (Decision 4)
 * keeps querystore data unambiguously identifiable next to core's tables.
 */
final class MysqlSchemaManager {

	// Resource-type names are validated on every upsert / search path; precompile so the hot path
	// doesn't recompile the same regex per call.
	private static final Pattern RESOURCE_TYPE_PATTERN = Pattern.compile("[a-z][a-z0-9_]*");

	// Bookkeeping tables that match the `querystore_%` metadata probe but aren't per-type document
	// indices (no resource_uuid / patient_uuid columns). Excluded from listAllTables() so wildcard
	// reads don't issue per-type-index SQL against them. See issue #11.
	private static final Set<String> STATE_TABLES = Collections.singleton("querystore_bootstrap_progress");

	private final DbSessionFactory sessionFactory;

	// v1 single-JVM assumption: `knownTables` reflects DDL issued by this JVM. A sibling node
	// creating a table elsewhere is invisible until `listAllTables()` is called explicitly.
	private final Set<String> knownTables = ConcurrentHashMap.newKeySet();

	MysqlSchemaManager(DbSessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	/** Idempotently creates the {@code querystore_<resourceType>} table if missing. */
	void ensureTable(String resourceType) {
		String table = tableName(resourceType);
		if (knownTables.contains(table)) {
			return;
		}
		try {
			JdbcSupport.inTransaction(sessionFactory, conn -> {
				if (!tableExists(conn, table)) {
					createTable(conn, table);
				}
			});
			knownTables.add(table);
		}
		catch (RuntimeException e) {
			throw new IllegalStateException("Could not ensure schema for " + table, e);
		}
	}

	void dropTable(String resourceType) {
		String table = tableName(resourceType);
		try {
			JdbcSupport.inTransaction(sessionFactory, conn -> {
				try (Statement stmt = conn.createStatement()) {
					stmt.executeUpdate("DROP TABLE IF EXISTS " + table);
				}
			});
			knownTables.remove(table);
		}
		catch (RuntimeException e) {
			throw new IllegalStateException("Could not drop " + table, e);
		}
	}

	/**
	 * Returns the cached set of known tables — populated by {@link #ensureTable(String)} and
	 * {@link #listAllTables()}. Cheap; safe to call on hot search paths. Empty until at least one
	 * table has been ensured or enumerated.
	 */
	Set<String> getKnownTables() {
		return new HashSet<>(knownTables);
	}

	/**
	 * Returns the names of every {@code querystore_*} table currently in the database. Used by
	 * cross-table operations like {@link MysqlBackendStore#bulkDeleteByPatient(String)} where the
	 * caller does not know which types contain documents for a given patient.
	 */
	Set<String> listAllTables() {
		Set<String> tables = new HashSet<>();
		try {
			JdbcSupport.inTransaction(sessionFactory, conn -> {
				DatabaseMetaData md = conn.getMetaData();
				try (ResultSet rs = md.getTables(conn.getCatalog(), null, QueryStoreConstants.INDEX_PREFIX + "%",
				    new String[] { "TABLE" })) {
					while (rs.next()) {
						String name = rs.getString("TABLE_NAME").toLowerCase();
						if (!STATE_TABLES.contains(name)) {
							tables.add(name);
						}
					}
				}
			});
		}
		catch (RuntimeException e) {
			throw new IllegalStateException("Could not enumerate querystore tables", e);
		}
		knownTables.addAll(tables);
		return tables;
	}

	static String tableName(String resourceType) {
		if (resourceType == null || !RESOURCE_TYPE_PATTERN.matcher(resourceType).matches()) {
			throw new IllegalArgumentException("Invalid resource type (must match [a-z][a-z0-9_]*): " + resourceType);
		}
		return QueryStoreConstants.INDEX_PREFIX + resourceType;
	}

	private boolean tableExists(Connection conn, String table) throws SQLException {
		DatabaseMetaData md = conn.getMetaData();
		try (ResultSet rs = md.getTables(conn.getCatalog(), null, table, new String[] { "TABLE" })) {
			return rs.next();
		}
	}

	private void createTable(Connection conn, String table) throws SQLException {
		String ddl = "CREATE TABLE " + table + " ("
		        + "  id BIGINT NOT NULL AUTO_INCREMENT,"
		        + "  resource_uuid CHAR(38) NOT NULL,"
		        + "  patient_uuid CHAR(38) NOT NULL,"
		        + "  record_date DATE NULL,"
		        + "  text MEDIUMTEXT,"
		        + "  embedding MEDIUMBLOB,"
		        + "  metadata_json MEDIUMTEXT,"
		        + "  last_modified DATETIME(3) NULL,"
		        + "  PRIMARY KEY (id),"
		        + "  UNIQUE KEY uq_" + table + "_resource (resource_uuid),"
		        + "  KEY idx_" + table + "_patient (patient_uuid),"
		        + "  KEY idx_" + table + "_date (record_date),"
		        + "  FULLTEXT KEY ft_" + table + "_text (text)"
		        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
		try (Statement stmt = conn.createStatement()) {
			stmt.executeUpdate(ddl);
		}
	}
}
