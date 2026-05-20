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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
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

	private static final Log log = LogFactory.getLog(MysqlSchemaManager.class);

	// Bookkeeping tables that match the `querystore_%` metadata probe but aren't per-type document
	// indices (no resource_uuid / patient_uuid columns). Excluded from listAllTables() so wildcard
	// reads don't issue per-type-index SQL against them. See issue #11.
	private static final Set<String> STATE_TABLES = Collections.singleton("querystore_bootstrap_progress");

	private final DbSessionFactory sessionFactory;

	// v1 single-JVM assumption: `knownTables` reflects DDL issued by this JVM, plus any tables
	// discovered by `listAllTables()` probes. Grow-only across the JVM's lifetime: a sibling node
	// that drops a previously-discovered table is invisible here until restart. The set staying
	// "too large" is benign — a subsequent bulk operation against the dropped table fails its
	// per-table catch, logs a `DocFailure`, and continues. Do NOT add cleanup that removes
	// sibling-dropped entries: that would reintroduce the discovery-vs-drop race the v1 contract
	// deliberately accepts.
	private final Set<String> knownTables = ConcurrentHashMap.newKeySet();

	// Memoized result of the INFORMATION_SCHEMA probe done by listAllTables(). The probe is hit
	// on every cross-type wildcard read (search, bulkDelete, exists) and produced a real JDBC
	// round-trip per call before this cache landed — 3x per chartsearchai patient search.
	// Cleared whenever this JVM mutates the table set (ensureTable creates a new one, dropTable
	// removes one), so steady-state reads serve from cache while local DDL stays consistent.
	// Cross-JVM staleness: a sibling node creating or dropping a table is invisible to this cache
	// until this JVM next runs its own DDL or restarts — accepted by the v1 single-JVM contract.
	// Hit-path callers receive the cached snapshot wrapped in {@link Collections#unmodifiableSet}
	// so a future caller can't silently corrupt the cache by mutating the returned set.
	private volatile Set<String> cachedAllTables;

	// One-shot dedup for "skipped stale name" WARN logs. Without this, every wildcard read on a
	// JVM with an upgrade-era stale directory would emit the same warning — log spam. With it,
	// the operator sees each artefact name exactly once per JVM lifetime.
	private final Set<String> warnedStaleNames = ConcurrentHashMap.newKeySet();

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
			cachedAllTables = null;
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
			cachedAllTables = null;
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
	 *
	 * <p>Results are memoized for the JVM lifetime; the cache is invalidated whenever this JVM
	 * mutates the table set via {@link #ensureTable(String)} or {@link #dropTable(String)}.
	 * Names that don't match {@link QueryStoreConstants#RESOURCE_TYPE_PATTERN} (e.g. stale {@code querystore_LegacyObs}
	 * leftover from a previous version with mixed-case identifiers) are filtered out so they
	 * don't bleed into wildcard search results — the same regex that gates writes gates reads.
	 */
	Set<String> listAllTables() {
		Set<String> cached = cachedAllTables;
		if (cached != null) {
			return cached;
		}
		Set<String> tables = new HashSet<>();
		try {
			JdbcSupport.inTransaction(sessionFactory, conn -> {
				DatabaseMetaData md = conn.getMetaData();
				try (ResultSet rs = md.getTables(conn.getCatalog(), null, QueryStoreConstants.INDEX_PREFIX + "%",
				    new String[] { "TABLE" })) {
					while (rs.next()) {
						String name = rs.getString("TABLE_NAME").toLowerCase();
						if (STATE_TABLES.contains(name)) {
							continue;
						}
						String resourceType = name.substring(QueryStoreConstants.INDEX_PREFIX.length());
						if (!QueryStoreConstants.RESOURCE_TYPE_PATTERN.matcher(resourceType).matches()) {
							if (warnedStaleNames.add(name)) {
								log.warn("Ignoring querystore table '" + name + "' — resource-type '"
								        + resourceType + "' does not match " + QueryStoreConstants.RESOURCE_TYPE_REGEX
								        + " (likely leftover from a prior version or a manual rename;"
								        + " drop the table to silence this warning).");
							}
							continue;
						}
						tables.add(name);
					}
				}
			});
		}
		catch (RuntimeException e) {
			throw new IllegalStateException("Could not enumerate querystore tables", e);
		}
		knownTables.addAll(tables);
		Set<String> snapshot = Collections.unmodifiableSet(tables);
		cachedAllTables = snapshot;
		return snapshot;
	}

	static String tableName(String resourceType) {
		if (resourceType == null || !QueryStoreConstants.RESOURCE_TYPE_PATTERN.matcher(resourceType).matches()) {
			throw new IllegalArgumentException("Invalid resource type (must match "
			        + QueryStoreConstants.RESOURCE_TYPE_REGEX + "): " + resourceType);
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
