/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.querystore.api.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.querystore.api.QueryStoreService;
import org.openmrs.module.querystore.backend.BackendStore;
import org.openmrs.module.querystore.backend.DocFailure;
import org.openmrs.module.querystore.backend.Filter;
import org.openmrs.module.querystore.backend.SearchRequest;
import org.openmrs.module.querystore.backend.SearchResult;
import org.openmrs.module.querystore.backend.WriteResult;
import org.openmrs.module.querystore.bootstrap.BootstrapService;
import org.openmrs.module.querystore.embedding.EmbeddingProvider;
import org.openmrs.module.querystore.model.QueryDocument;

/**
 * Default {@link QueryStoreService}. Delegates writes and search to the configured
 * {@link BackendStore}; hybrid fusion is the SPI's default-method responsibility (BM25 + kNN with
 * rank-based RRF — backends with native fusion may override; see ADR Decision 3 SPI sub-point 2).
 * The {@link EmbeddingProvider} dependency is optional: when null, search degrades to BM25-only.
 */
public class QueryStoreServiceImpl extends BaseOpenmrsService implements QueryStoreService {

	private static final Log log = LogFactory.getLog(QueryStoreServiceImpl.class);

	private BackendStore backend;

	private EmbeddingProvider embeddingProvider;

	// Test seam: when non-null, replaces the Context.getService(BootstrapService.class) lookup so
	// unit tests can pin the auto-index path without a live OpenMRS Spring context. Production
	// wiring leaves this null and resolves through Context — see ensureIndexedSafely. Package-private
	// to match the seam shape used in BootstrapServiceImpl.providersOverride.
	private BootstrapService bootstrapServiceOverride;

	public void setBackend(BackendStore backend) {
		this.backend = backend;
	}

	public void setEmbeddingProvider(EmbeddingProvider embeddingProvider) {
		this.embeddingProvider = embeddingProvider;
	}

	void setBootstrapServiceOverride(BootstrapService bootstrapService) {
		this.bootstrapServiceOverride = bootstrapService;
	}

	@Override
	public WriteResult index(QueryDocument document) {
		if (document == null) {
			return WriteResult.failed(new DocFailure(null, null, "document was null", false));
		}
		if (document.getResourceUuid() == null) {
			return WriteResult.failed(new DocFailure(document.getResourceType(), null,
			        "document resource_uuid was null", false));
		}
		if (backend == null) {
			// Misconfiguration, not a per-doc problem — silently swallowing here is what produced the
			// "bootstrap reports 682 indexed but the index holds 0" failure mode the slice fixes.
			// Throwing lets the bootstrap dispatcher (and any other caller that cares about persistence
			// accuracy) react: TypeBootstrapper.projectOne catches RuntimeException to skip the record,
			// without incrementing documents_indexed, so the counter only reflects confirmed writes.
			throw new IllegalStateException(
			        "No BackendStore wired into QueryStoreServiceImpl; cannot index "
			                + document.getResourceType() + "/" + document.getResourceUuid()
			                + ". Production: check wireBackend() in QueryStoreActivator.started() and "
			                + "the querystore.backend GP value. Tests: call setBackend() before "
			                + "exercising the index path.");
		}
		return backend.upsert(document);
	}

	@Override
	public void delete(String resourceType, String resourceUuid) {
		if (resourceType == null || resourceUuid == null) {
			return;
		}
		if (backend == null) {
			return;
		}
		backend.delete(resourceType, resourceUuid);
	}

	@Override
	public void bulkDeleteByPatient(String patientUuid) {
		if (patientUuid == null) {
			return;
		}
		if (backend == null) {
			log.warn("No BackendStore wired; ignoring bulkDeleteByPatient call for " + patientUuid);
			return;
		}
		backend.bulkDeleteByPatient(patientUuid);
	}

	@Override
	public List<QueryDocument> searchByPatient(String patientUuid, String query, int limit) {
		if (backend == null || patientUuid == null) {
			return Collections.emptyList();
		}
		if (!backend.existsByPatient(patientUuid)) {
			ensureIndexedSafely(patientUuid);
		}
		List<QueryDocument> hits = runHybrid(query, limit, Filter.patientScope(patientUuid));
		hits = prependObsTrendSyntheses(hits);
		emitRetrievalLog(query, hits);
		return hits;
	}

	/**
	 * Minimum number of numeric obs records for the same concept before the synthesizer emits a
	 * trend summary. Below the threshold there's no meaningful trend to summarize — listing
	 * 2 or 3 BP readings individually is already the right shape for the LLM.
	 */
	private static final int OBS_TREND_MIN_READINGS = 5;

	/**
	 * Adds query-time synthesis records summarizing repeated numeric obs (e.g.
	 * {@code "Systolic blood pressure trend: 22 readings 2020-2026; range 105-159 mmHg; last 145
	 * mmHg on 2026-02-28"}). Strictly additive: the individual readings stay in the hit list at
	 * their original ranks; the synthesis is prepended so a small LLM has an inline summary at
	 * the top of the chart without losing access to the raw points.
	 *
	 * <p>Aggregation is over the RETURNED hits, not the patient's full history. The bias toward
	 * BM25-surfaced readings is intentional: the synthesis describes what's in this chart, so
	 * the LLM's reasoning matches what it can cite. A patient with 200 BP readings whose top-30
	 * are the most recent 30 yields a synthesis over those 30, which is the clinically relevant
	 * window for "current BP trend" anyway.
	 *
	 * <p>Query-time only: nothing is persisted to the backend. Synthesis recomputes on every
	 * call, which keeps it always-current with concept-name updates and avoids the
	 * cross-process consistency story that an index-time synthesis would need (every new obs
	 * would invalidate the prior trend).
	 */
	private static List<QueryDocument> prependObsTrendSyntheses(List<QueryDocument> hits) {
		if (hits == null || hits.size() < OBS_TREND_MIN_READINGS) {
			return hits;
		}
		java.util.Map<String, List<QueryDocument>> obsByConcept = new java.util.LinkedHashMap<>();
		for (QueryDocument d : hits) {
			if (d == null || !"obs".equals(d.getResourceType())) {
				continue;
			}
			Object conceptUuid = d.getMetadata().get(
					org.openmrs.module.querystore.QueryStoreConstants.FIELD_CONCEPT_UUID);
			Object valueNumeric = d.getMetadata().get(
					org.openmrs.module.querystore.QueryStoreConstants.FIELD_VALUE_NUMERIC);
			if (!(conceptUuid instanceof String) || !(valueNumeric instanceof Number)) {
				continue;
			}
			obsByConcept.computeIfAbsent((String) conceptUuid, k -> new ArrayList<>()).add(d);
		}
		List<QueryDocument> syntheses = null;
		for (List<QueryDocument> group : obsByConcept.values()) {
			if (group.size() < OBS_TREND_MIN_READINGS) {
				continue;
			}
			QueryDocument synth = synthesizeTrend(group);
			if (synth != null) {
				if (syntheses == null) {
					syntheses = new ArrayList<>();
				}
				syntheses.add(synth);
			}
		}
		if (syntheses == null) {
			return hits;
		}
		List<QueryDocument> out = new ArrayList<>(syntheses.size() + hits.size());
		out.addAll(syntheses);
		out.addAll(hits);
		return out;
	}

	/**
	 * Builds the trend synthesis QueryDocument for one (concept, patient) group. Computes count,
	 * date span, value range, and last value from the obs metadata. Returns null when the group
	 * has no concept name or no usable numeric values — in those cases the original obs records
	 * carry the chart on their own.
	 */
	private static QueryDocument synthesizeTrend(List<QueryDocument> group) {
		QueryDocument first = group.get(0);
		Object conceptNameObj = first.getMetadata().get(
				org.openmrs.module.querystore.QueryStoreConstants.FIELD_CONCEPT_NAME);
		if (!(conceptNameObj instanceof String) || ((String) conceptNameObj).isEmpty()) {
			return null;
		}
		String conceptName = (String) conceptNameObj;
		Object unitsObj = first.getMetadata().get(
				org.openmrs.module.querystore.QueryStoreConstants.FIELD_UNITS);
		String units = unitsObj instanceof String ? (String) unitsObj : "";
		double min = Double.POSITIVE_INFINITY;
		double max = Double.NEGATIVE_INFINITY;
		java.time.LocalDate earliest = null;
		java.time.LocalDate latest = null;
		Double lastValue = null;
		for (QueryDocument d : group) {
			Object v = d.getMetadata().get(
					org.openmrs.module.querystore.QueryStoreConstants.FIELD_VALUE_NUMERIC);
			if (!(v instanceof Number)) {
				continue;
			}
			double val = ((Number) v).doubleValue();
			if (val < min) min = val;
			if (val > max) max = val;
			java.time.LocalDate date = d.getDate();
			if (date != null) {
				if (earliest == null || date.isBefore(earliest)) {
					earliest = date;
				}
				if (latest == null || date.isAfter(latest)) {
					latest = date;
					lastValue = val;
				}
			}
		}
		if (min == Double.POSITIVE_INFINITY) {
			return null;
		}
		StringBuilder sb = new StringBuilder(conceptName).append(" trend: ")
				.append(group.size()).append(" readings");
		if (earliest != null && latest != null) {
			sb.append(' ').append(earliest.getYear());
			if (latest.getYear() != earliest.getYear()) {
				sb.append('-').append(latest.getYear());
			}
		}
		sb.append("; range ").append(formatTrendNumber(min))
				.append('-').append(formatTrendNumber(max));
		if (!units.isEmpty()) {
			sb.append(' ').append(units);
		}
		if (lastValue != null && latest != null) {
			sb.append("; last ").append(formatTrendNumber(lastValue));
			if (!units.isEmpty()) {
				sb.append(' ').append(units);
			}
			sb.append(" on ").append(latest);
		}
		QueryDocument synth = new QueryDocument();
		synth.setResourceType("obs");
		synth.setResourceUuid("trend-" + first.getMetadata().get(
				org.openmrs.module.querystore.QueryStoreConstants.FIELD_CONCEPT_UUID));
		synth.setPatientUuid(first.getPatientUuid());
		synth.setDate(latest);
		synth.setText(sb.toString());
		return synth;
	}

	private static String formatTrendNumber(double v) {
		if (v == Math.floor(v) && !Double.isInfinite(v)) {
			return Long.toString((long) v);
		}
		return Double.toString(v);
	}

	/**
	 * Retrieval-debug log: one {@code [QSEVAL]} line per hit at DEBUG level so the eval harness
	 * ({@code /tmp/qs_smoke_eval.py}) can grep its rubric against the actual retrieval order
	 * when querystore DEBUG is enabled. DEBUG (not INFO) because the line emits patient record
	 * text — INFO would land PHI in production server logs by default. Operators running an
	 * eval pass against a deployment enable DEBUG on this package via log4j2 config; production
	 * pays nothing because the {@link Log#isDebugEnabled()} guard short-circuits before any
	 * string concatenation.
	 */
	private void emitRetrievalLog(String query, List<QueryDocument> hits) {
		if (!log.isDebugEnabled() || hits == null || hits.isEmpty()) {
			return;
		}
		String safeQuery = query == null ? "" : query.replace('\n', ' ').replace('\r', ' ');
		for (int i = 0; i < hits.size(); i++) {
			QueryDocument d = hits.get(i);
			String text = d == null || d.getText() == null
					? "" : d.getText().replace('\n', ' ').replace('\r', ' ');
			log.debug("[QSEVAL] q=[" + safeQuery + "] rank=" + (i + 1)
					+ " type=" + (d == null ? "null" : d.getResourceType())
					+ " uuid=" + (d == null ? "null" : d.getResourceUuid())
					+ " text=[" + text + "]");
		}
	}

	/** Lazy lookup avoids a Spring circular dependency: {@code BootstrapService} depends on
	 *  {@link QueryStoreService}, so resolving the reverse direction at wiring time would surface
	 *  the cycle in bean construction. Context lookup defers it to call time, when both beans are
	 *  fully wired. An unavailable service (no Spring context in test envs; narrow activation
	 *  window) throws from Context.getService and is absorbed by the catch — the search still runs
	 *  and returns whatever the backend has. */
	private void ensureIndexedSafely(String patientUuid) {
		try {
			BootstrapService bootstrapService = bootstrapServiceOverride;
			if (bootstrapService == null) {
				bootstrapService = Context.getService(BootstrapService.class);
			}
			bootstrapService.ensureIndexed(patientUuid);
		}
		catch (RuntimeException e) {
			// Index-failure must not block search; whatever did get indexed (or what was already
			// present) is still searchable. Empty results are the same outcome as before this
			// feature shipped.
			log.warn("Auto-index for patient " + patientUuid
			        + " failed; serving search with whatever is indexed", e);
		}
	}

	@Override
	public List<QueryDocument> search(String query, int limit) {
		if (backend == null) {
			return Collections.emptyList();
		}
		return runHybrid(query, limit, null);
	}

	private List<QueryDocument> runHybrid(String query, int limit, Filter scope) {
		if (StringUtils.isBlank(query) || limit <= 0) {
			return Collections.emptyList();
		}
		SearchRequest.Builder req = SearchRequest.builder().queryText(query).limit(limit);
		if (scope != null) {
			req.filter(scope);
		}
		if (embeddingProvider != null) {
			req.queryVector(embeddingProvider.embedQuery(query));
		}
		return toDocuments(backend.hybrid(req.build()));
	}

	private static List<QueryDocument> toDocuments(SearchResult result) {
		List<QueryDocument> out = new ArrayList<>(result.getHits().size());
		result.getHits().forEach(h -> out.add(h.getDocument()));
		return out;
	}
}
