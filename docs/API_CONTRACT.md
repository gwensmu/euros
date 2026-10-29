# Euros synchronization contract — v1

The Android app and MCP tools share the server's domain service and accepted records. This is the first implementation slice, not the complete release API. JSON uses snake_case. Record responses also include server `updated_at` timestamps. All timestamps include a UTC offset. Health data is never logged in HTTP diagnostics.

## Authentication and transport

Use HTTPS outside a loopback development environment. The initial development service accepts a configured bearer token. Never commit a token or embed one in an APK. This first authentication adapter is not a claim of ChatGPT OAuth compatibility; remote client connection, revocation and scopes must be verified before deployment. All implemented routes require authentication; there is no public record or health endpoint.

## Write a record

`POST /v1/commands`

```json
{
  "command_id": "example-command-001",
  "entity_type": "activities",
  "entity_id": "example-activity-001",
  "expected_revision": 0,
  "operation": "upsert",
  "payload": {
    "title": "Weights",
    "kind": "weights",
    "scheduled_at": "2026-10-14T18:00:00-05:00",
    "status": "pending",
    "plan_revision": "contract-fixture"
  }
}
```

Example values are test data, never an automatically installed user plan. Use globally unique IDs in real clients. `expected_revision: 0` creates; subsequent writes carry the revision read by that client. Upsert sends the full validated payload. Supported first-slice types are `activities`, `food_entries`, and `weight_entries`; operations are `upsert` and `delete`.

Successful response:

```json
{
  "record": {
    "entity_type": "activities",
    "entity_id": "example-activity-001",
    "revision": 1,
    "deleted": false,
    "payload": {
      "title": "Weights",
      "kind": "weights",
      "scheduled_at": "2026-10-14T18:00:00-05:00",
      "status": "pending",
      "plan_revision": "contract-fixture"
    }
  },
  "cursor": 1
}
```

Replaying an identical command ID and body returns the original result without creating another revision. Reusing a command ID for a different body is a conflict. A stale expected revision is also a conflict. Clients retain the local proposal and the server record; they must not silently retry the proposal against a new revision. Deletion creates a tombstone with a new revision and change cursor.

## Read changes

`GET /v1/changes?after=0&limit=100`

Response has `changes` (record fields plus `cursor`), `next_cursor`, and `has_more`. Cursors are server-assigned monotonic positions, not client timestamps. Revisions and cursors must fit signed 64-bit nonnegative integers. Start from zero for initial synchronization. Apply each page and advance its cursor in one local transaction. Replayed pages must be harmless. Fetch pages until `has_more` is false. Never remove a local pending write simply because an older remote record appears in a page.

## Entity payloads

- Activity: `title`, `kind`, `scheduled_at`, `status` (`pending`, `done`, `skipped`), and `plan_revision`. Weights records completion only; exercise prescriptions remain plan data. BJJ does not contain tactical notes.
- Food: `consumed_at`, `local_date`, `raw_text`, and `nutrients` with nullable `kcal`, `protein_g`, `carbs_g`, `fat_g`. Preserve known zero separately from unknown/null. Nutrients accept up to 1e9 with at most six decimal fractional places; reject nonfinite values. The initial slice accepts already-known totals; it does not pretend to have interpreted natural language or queried a food database.
- Weight: `measured_at`, `local_date`, `weight_lb`. A positive observed measurement is required; nothing is inferred from a goal.

`local_date` must match the calendar date in the offset-bearing consumed/measured timestamp. `GET /v1/records?entity_type=activities` returns current nondeleted records. `GET /v1/nutrition?local_date=YYYY-MM-DD` returns entry count and per-nutrient `known_total` decimal strings and `missing_count`. The server validators define required fields, bounds and allowed kinds. API errors must be handled as errors; a client must never treat a non-2xx response as a successful log. Tests exercise authentication, invalid input, stale revisions, changed retry bodies, and identical retry bodies.

## Phone outbox

Persist the local record and pending command together before updating UI. Only one command per record is in flight. A changed local record after a submitted command requires a subsequent command based on the accepted revision; do not mutate the body of an already-submitted command ID. Connection failure leaves the command queued. Conflict retains both versions for explicit resolution. Accepted writes and remote changes update the same local table.

## MCP

MCP read and write tools delegate to the same service as the REST routes. A parsing/proposal tool may not save intake. Write tools receive stable command IDs and expected revisions, and are distinguished from read tools. Connecting a client does not seed logs, deploy a service or send messages.

## Follow-on contracts

Health Connect record batches, HR samples, plan snapshots/target history, natural-language draft interpretation, generated wrap-ups, recovery quantity records and scoped remote authentication require subsequent typed contracts. No generic record payload should be used to bypass their domain validation.

## Model proposals

Authenticated app calls, separate from all record mutation endpoints:

- `POST /v1/interpret/food` with `{ "text": "3 eggs and 80g corn casserole" }` returns `raw_text`, `quantity_g`, nullable decimal-string `nutrients` (`kcal`, `protein_g`, `carbs_g`, `fat_g`), optional `clarification`, and `interpretation` provider/model provenance. Response is a draft; saving still uses `/v1/commands` with a stable entry ID. Reasonable nutrition estimates are supported without adding estimate labels in Android.
- `POST /v1/review/week` with Monday `week_start` and a bounded `records` array of the existing wire record shape returns `summary`, `adjustments`, `coverage`, `week_start`, `provider`, and `model`. Snapshot records may have revision0 for unsynced local data. Android selects the week, related recovery items and prior designated weights; raw Health Connect metadata is not sent. The backend validates the snapshot and calculates facts before asking the model to write prose. No records or plans are changed.

Use the app bearer token for HTTP inference. Model credentials are server-only. Failures return structured errors, including `model_not_configured` (503), `model_timeout` (504), `model_refused` (422), and invalid/incomplete provider output (502). Manual logging remains independent of model availability.

MCP `interpret_food(text)` and `review_week(week_start, records)` call these same services using the MCP bearer credential; they are proposal-only and never mutate records. Food interpretation metadata includes `prompt_version`. Weekly responses also preserve `prompt_version`, `generated`, and checked `evidence_record_ids`; an empty logged week returns a deterministic message with `generated: false`, null provider/model, and no model call. Evidence IDs constrain references to supplied records but do not prove every sentence semantically correct.

## Source-owned tracker workouts (phone upload; read-only MCP)

Tracker snapshots use separate `tracker_workouts` and `tracker_commands` tables in the same persistent SQLite database. They never enter manual `records`, ordinary `changes`, food totals, or activity completion. Existing schema v1 tables are untouched; the tracker extension creates its additional tables at application setup. SQLite backups include both. This contract is implemented locally; deployment and personal data upload are separate steps.

`POST /v1/tracker/import` requires the **app** token. MCP has no corresponding writer; `apply_command` rejects `tracker_workouts`. Body limit is 4 MiB, with 1–20 records per atomic batch and at most 20,000 HR samples per workout. [Synthetic fixture](../contracts/tracker-import.json) is contract data only, never an authored or imported personal record.

Envelope: `{command_id, records:[...]}`. Each live record contains:

- `source_key`: stable opaque key, 1–500 characters; `expected_revision`: accepted tracker revision (0 for creation).
- `source_package`: exactly `fi.polar.polarflow`; `source_id`: original Health Connect exercise ID. The pair cannot change or be duplicated under another key.
- `source_modified_at`: original source modification instant; `observed_at`: time that source snapshot was observed. Offset timestamps required. Older source modification/observation than the accepted record is rejected; observations over five minutes ahead of server time are rejected.
- `deleted:false`, `start_at`, `end_at` (positive duration, at most 24 hours); optional `source_title` (nullable, at most 500 characters) and integer `exercise_type`.
- `activity_id`: null or an explicitly linked existing live activity ID. A workout and an activity link only once; final batch state is validated so an unlink/link transfer is independent of batch order. Linking never completes the activity.
- `heart_rate_samples:[{at,bpm,source_id,source_package}]`: original HR record identity/package, bpm integer 1–300, strictly increasing offset timestamps inside the workout. All sources must be Polar. Duplicate timestamps, unordered/out-of-window samples and nonfinite/invalid values are rejected. Keep unshared Fitbit movement separate.

Success: `{command_id,records:[{source_key,revision,deleted}]}`. Persist the exact immutable request before sending, and advance the phone checkpoint only after acknowledgement. Exact command retries return the stored receipt; reuse with different bytes/values returns 409. Wrong expected revision returns 409 `tracker_revision_conflict` with `source_key` and current metadata including revision, deletion state, source observation, server receipt time and content hash. A conflict is not permission to resubmit an older snapshot at a newer revision. Reconcile source/links explicitly. One invalid/conflicting record rolls back the entire batch.

Source deletion uses `{source_key,expected_revision,deleted:true,observed_at}`. It preserves the original snapshot internally, exposes a tombstone and removes it from live metrics. Never-uploaded tombstones should be omitted; an unknown source key returns 404. Local cache clearing or absent records outside the accessible import range must **not** be translated into server tombstones. A fresh, explicitly observed live source can replace a tombstone only at its current accepted revision. Manual annotations remain in their own records.

`GET /v1/tracker/workouts?date_from=YYYY-MM-DD&date_to=YYYY-MM-DD` requires app authentication and returns the same compact summaries as MCP `list_tracker_workouts`. The inclusive range is at most 366 dates, filtered by workout start in America/Chicago. Tombstones remain included for reconciliation. Responses retain revision/source identity, observed/received/source-modified times, interval, explicit link and sample/coverage/HR summaries, without raw curves.

### Read-only analysis tools

- `list_tracker_workouts(date_from,date_to)`: compact source-separated summaries for comparing sessions. A tracker workout is an observed session, not proof of fulfilling a prescription.
- `read_tracker_workout(source_key,zone_boundaries_bpm=null)`: detail with deterministic metrics, HR source IDs and a curve bounded to 600 points. Unknown keys return null; tombstones return metadata without metrics. `training_benefit` remains null. `polar_zone_definitions` describes the personal user-confirmed screenshot profile, not imported vendor metadata.

Metrics use original samples, not the downsampled curve. Coverage is the sum of adjacent sample intervals **≤60 seconds**. Each covered interval holds the preceding sample's bpm; gaps longer than 60 seconds are entirely uncovered. No leading/trailing extrapolation. `average_bpm` is the duration-weighted mean over covered time, null when no time is covered. `sample_average_bpm` is a separate arithmetic sample mean. `peak_bpm` is the actual maximum original sample. Missing HR is not zero. Fractional-second timestamps remain meaningful at the gap boundary.

The display curve uses sequential bucket minimum/maximum samples in original time order, preserving the actual maximum and bounding output at 600 points. It reports `curve_downsampled`, `curve_method` and original `sample_count`; do not calculate coverage or exposure from the reduced curve. Fixed five-minute elapsed windows report covered seconds and mean/peak over covered segments. These windows are **not rounds or inferred training intervals**.

Time bands are returned only with 1–10 explicit increasing integer bpm cutoffs (1–300). Bands are lower-inclusive/upper-exclusive, including below-first/above-last bands; time sums to covered time only. These are supplied analysis thresholds, not claimed Polar zones. Do not infer HRmax from observed peak or invent zone boundaries. Tools do not infer cortisol, CNS readiness, fatigue diagnosis, or a generated vendor training-benefit label. No model calls occur in these tools.

## Personal HR zone profile (October 14, 2026)

User-confirmed Polar screenshot boundaries are 95, 114, 133, 152, 171 and 190 bpm. Profile `personal-polar-2026-10-14` records that provenance; no screenshot or source health data is committed. Zones 1–5 use lower-inclusive, upper-exclusive intervals: [95,114), [114,133), [133,152), [152,171), [171,190). Below 95 and at/above 190 are separate buckets. This explicit Euros convention does not claim exact Polar boundary handling or totals.

Backend list/detail `metrics.hr_zones` returns named zones, bounds, covered seconds, out-of-range seconds, uncovered seconds and profile provenance. Custom caller `time_bands` remain separate and never replace the personal profile. Android recaps show estimated zone durations with bars scaled to the full session and explicit uncovered time; no-coverage zone durations display unknown. Both use previous-sample hold only between consecutive samples separated by at most 60 seconds (including fractional-second precision), without leading/trailing extrapolation. A last sample contributes peak information but no invented duration. Zone plus out-of-range time equals covered time. These read-time calculations do not modify source snapshots or canonical logs, infer HRmax, or recreate Polar's proprietary algorithm or training benefit.

## Goal-based nutrition policy

`GET /v1/nutrition?local_date=YYYY-MM-DD` and the corresponding MCP read additionally return `mode`, `maintenance_start` (nullable ISO date), and effective `targets` using the meal-plan target field names. Owner-authorized policy activates2050kcal/130P/250C/59F from the first saved weight at/below155lb dated October15,2026 or later, excluding future observations. Later higher weights do not undo it; correcting/deleting qualifying records recomputes the projection. Earlier dates retain loss/saved targets. Meal-plan payloads preserve their authored target snapshot; clients use effective nutrition targets for the selected date. No consumption records are changed.

## Reminder copy

`POST /v1/reminders/copy` requires the app token and accepts `{kind,context_key,allow_skip_joke,facts,recent_joke_ids}`. Kind is morning/training/recovery/food/sunday, context key is a64-character lowercase SHA256 hex string, facts are1–4000characters, recent IDs at most12. Request limit16KiB. The backend uses Luna low to select an approved optional joke ID only; deterministic phone copy supplies factual sentences. Response includes echoed context key, joke ID/text and prompt version. Unsupported/unknown jokes fail validation. Persistent cache binds actual input facts as well as caller key, model and copy version; cached results don't spend another call.12 reminder model reservations/day share existing rate/concurrency limits. No notifications are sent by this endpoint and no domain record is written.
