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
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.OpenmrsObject;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.embedding.EmbeddingProvider;
import org.openmrs.module.querystore.model.QueryDocument;
import org.openmrs.module.querystore.serialization.ClinicalRecordSerializer;

/**
 * Per-type backfill driver shared across resource types (ADR open question on initial bootstrap).
 * Walks core's transactional data ordered by effective dateChanged ascending, projects each
 * record through the type's {@link ClinicalRecordSerializer}, embeds the text, and writes via
 * {@link QueryStoreService#index(QueryDocument)} — which honors the conditional-upsert-by-version
 * SPI invariant so a slow scan can't overwrite a fresher concurrent AOP / event write.
 *
 * <p>Subclasses implement {@link #fetchPage}, {@link #getDateChanged}, and {@link #getUuid} for
 * their entity type. The {@link #run} loop persists the cursor after each page so an interrupted
 * scan resumes without re-projecting.
 */
public abstract class TypeBootstrapper<T> {

	private static final Log log = LogFactory.getLog(TypeBootstrapper.class);

	/** Page size for the paginated scan. Chosen to fit comfortably in memory across all types. */
	protected static final int PAGE_SIZE = 200;

	public abstract String getResourceType();

	protected abstract ClinicalRecordSerializer<T> getSerializer();

	/**
	 * Returns up to {@code pageSize} records whose {@code dateChanged ?? dateCreated} is strictly
	 * after the cursor, ordered ascending with {@code uuid} as tie-breaker. Implementations
	 * typically query core via HQL. The cursor is {@code null} on the first call; treat null as
	 * "from the beginning."
	 */
	protected abstract List<T> fetchPage(Instant afterDateChanged, String afterUuid, int pageSize);

	protected abstract Instant getDateChanged(T entity);

	/**
	 * Returns the entity's UUID for cursor tie-breaking. Default reads it via
	 * {@link OpenmrsObject#getUuid()}; subclasses override only when the entity is not an
	 * {@code OpenmrsObject} (test fixtures, exotic SPI types).
	 */
	protected String getUuid(T entity) {
		return entity instanceof OpenmrsObject ? ((OpenmrsObject) entity).getUuid() : null;
	}

	public final void run(BootstrapProgress progress, QueryStoreService service, EmbeddingProvider embedder,
	                      BootstrapProgressDao progressDao) {
		progress.setStatus(BootstrapStatus.RUNNING);
		if (progress.getStartedAt() == null) {
			progress.setStartedAt(Instant.now());
		}
		progress.setFailureMessage(null);
		progressDao.save(progress);

		try {
			while (true) {
				List<T> page = fetchPage(progress.getCursorDateChanged(), progress.getCursorUuid(), PAGE_SIZE);
				if (page.isEmpty()) {
					break;
				}
				for (T entity : page) {
					indexOne(entity, progress, service, embedder);
				}
				progressDao.save(progress);
			}
			progress.setStatus(BootstrapStatus.COMPLETED);
			progress.setCompletedAt(Instant.now());
			progressDao.save(progress);
		}
		catch (RuntimeException e) {
			log.warn("Bootstrap failed for " + getResourceType() + " at cursor "
			        + progress.getCursorDateChanged() + "/" + progress.getCursorUuid(), e);
			progress.setStatus(BootstrapStatus.FAILED);
			// Include the exception class so a null-message failure (NPE, ISE) is still identifiable
			// from the persisted row alone; getMessage() can be null and would store as "null".
			progress.setFailureMessage(e.getClass().getSimpleName() + ": " + e.getMessage());
			progressDao.save(progress);
			throw e;
		}
	}

	private void indexOne(T entity, BootstrapProgress progress, QueryStoreService service,
	                      EmbeddingProvider embedder) {
		try {
			QueryDocument doc = getSerializer().serialize(entity);
			if (doc != null) {
				doc.setEmbedding(embedder.embed(doc.getText()));
				service.index(doc);
				progress.setDocumentsIndexed(progress.getDocumentsIndexed() + 1);
			}
		}
		catch (RuntimeException e) {
			// Per-entity skip on failure: a poison record (one that consistently fails to serialize,
			// embed, or index) must not permanently stall the bootstrap. The full stacktrace is
			// logged so an admin can investigate, and the cursor still advances past the entity in
			// the finally below so retry doesn't replay the same failure forever.
			log.warn("Skipping " + getResourceType() + "/" + getUuid(entity) + " due to failure", e);
		}
		finally {
			// Advance the cursor whether or not a document was produced (null-serializing records
			// like group-obs parents) and whether or not the index call succeeded (per-entity
			// failure is treated as a skip); resume must not revisit them.
			progress.setCursorDateChanged(getDateChanged(entity));
			progress.setCursorUuid(getUuid(entity));
		}
	}
}
