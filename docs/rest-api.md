# Query Store REST API

The query store's primary consumer surface is the in-process Java service
(`QueryStoreService`); see [ADR Decision 14](./adr.md#decision-14-authorization-and-consumer-api-surface).
These REST endpoints are **operational tooling, not the query surface** — an *observability* read
(is this deployment fully indexed?) and an *administrative maintenance* trigger (re-index one
patient without a restart). They exist because a deployment seeded by a SQL dump bypasses the live
indexing bridge and depends on the background bootstrap completing, and that completion was
otherwise unobservable and unrepairable on a live instance.

All endpoints are served under the OpenMRS REST namespace (the module requires the
`webservices.rest` module):

```
/ws/rest/v1/querystore/...
```

Authenticate with any OpenMRS REST mechanism (HTTP Basic shown below).

---

## GET `/ws/rest/v1/querystore/indexingstatus`

Returns the per-resource-type bootstrap (initial-backfill) status, plus a single derived
`complete` flag answering **"is this deployment fully indexed?"**.

- **Privilege:** `Get Patients` — a `failureMessage` can contain internal record/patient
  identifiers, so it is gated like the query store's read API.
- **Request body:** none.

**Response `200`:**

```json
{
  "complete": false,
  "types": [
    {
      "resourceType": "obs",
      "status": "RUNNING",
      "documentsIndexed": 320795,
      "cursorDateChanged": "2006-05-25T12:41:31Z",
      "startedAt": "2026-05-30T19:35:29.849Z",
      "completedAt": null,
      "failureMessage": null,
      "backend": "lucene"
    },
    {
      "resourceType": "diagnosis",
      "status": "FAILED",
      "documentsIndexed": 0,
      "cursorDateChanged": null,
      "startedAt": "2026-05-29T13:31:01.404Z",
      "completedAt": null,
      "failureMessage": "FetchNotFoundException: Entity `org.openmrs.Patient` with identifier value `49` does not exist",
      "backend": "lucene"
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `complete` | `true` **only** when at least one type is tracked **and every** tracked type is `COMPLETED`. Any `RUNNING`/`FAILED`/`NOT_STARTED` type — or an empty progress table — yields `false`. This is the headline "fully indexed?" answer. |
| `types[].status` | `NOT_STARTED` \| `RUNNING` \| `COMPLETED` \| `FAILED`. |
| `types[].documentsIndexed` | Documents written for that type so far. |
| `types[].cursorDateChanged` | The backfill resume cursor (ascending `dateChanged`); records changed after this are not yet indexed. ISO-8601, or `null`. |
| `types[].startedAt` / `completedAt` | ISO-8601 timestamps, or `null`. |
| `types[].failureMessage` | Populated when `status` is `FAILED` (e.g. an orphaned row referencing a deleted patient). |
| `types[].backend` | The backend that row was written against (`lucene` \| `mysql` \| `elasticsearch`). |

```bash
curl -s -u admin:Admin123 \
  https://your-server/openmrs/ws/rest/v1/querystore/indexingstatus
```

---

## POST `/ws/rest/v1/querystore/reindex`

Forces a full re-projection of **one patient** — deletes that patient's read-store documents and
re-indexes every resource type from core — then reports the resulting document count. This is the
only no-restart way to repair a **partially-indexed** patient (e.g. one whose recent records
arrived via a SQL dump): the lazy cold-touch projection refuses such patients because they already
have *some* documents.

- **Privilege:** `Manage Global Properties` — this is a destructive, expensive maintenance
  operation (it deletes before rebuilding), so it requires a system-administration privilege,
  stricter than the read endpoint's `Get Patients`.
- **Request body:** `{"patient": "<patient-uuid>"}`.
- **Synchronous:** runs in the request thread; latency scales with the patient's record count
  (each record is re-embedded). Bounded to one patient.

**Response `200`:**

```json
{ "patient": "dd9836d7-1691-11df-97a5-7038c432aabf", "documentsIndexed": 154 }
```

**Response `400`** (missing/blank `patient`):

```json
{ "error": "patient is required" }
```

```bash
curl -s -X POST -u admin:Admin123 \
  -H 'Content-Type: application/json' \
  -d '{"patient":"dd9836d7-1691-11df-97a5-7038c432aabf"}' \
  https://your-server/openmrs/ws/rest/v1/querystore/reindex
```

**Semantics & caveats:**

- **Delete-before-reproject, under a per-patient lock.** The delete and re-projection are atomic
  with respect to the lazy cold-touch projection (they share the same per-patient lock), so a
  concurrent first-touch can't interleave with the delete.
- **Transient partial reads.** A concurrent search for the *same* patient may briefly observe
  partial or empty results while the rebuild is in flight (the read path's existence probe runs
  outside the lock). The full chart is restored by the time this call returns. Avoid triggering a
  reindex during active charting on that patient.
- **Concurrent bridge writes converge.** A live `saveObs` (etc.) for the patient during the reindex
  is reconciled by the version-protection invariant (newer write wins, older dropped) — no
  corruption.
- **Per type, not global.** This repairs one patient. To advance the *global* backfill (e.g. retry
  a `FAILED` type after fixing its data), use the bootstrap path (`querystore.bootstrap.autostart`
  on module start, or `BootstrapService.bootstrap(resourceType)`); it does not have a REST trigger.
