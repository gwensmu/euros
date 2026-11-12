# Your training companion
## A technology-agnostic implementation blueprint and AI build recipe

Edition: October 15, 2026. Based on the implemented Euros personal training app, with identifying information and personal records removed.

**Give this entire document to your own coding AI.** It describes what to recreate, which choices to personalize, and how to verify the result. Your AI chooses the language, framework, database, model provider, operating system integration, deployment tools, and tunnel provider. Nothing here requires a particular vendor or programming stack.

The companion HTML edition includes reference layouts. Both editions contain the same specification; the HTML adds visual sketches, not another set of requirements. Illustrative dates, foods, exercise prescriptions and numbers are fictional UI fixtures, never a recommended diet or training program.

## 1. What you are building

A private, single-person app for preparing for a specific training goal or competition—for example, a Brazilian jiu-jitsu tournament. The goal and its target date organize the training phases, weekly schedule, recovery routines and nutrition plan. The app turns that preparation into concrete daily actions and makes progress toward the goal visible.

It answers three questions: what should I do next, what have I logged, and how is my training week going? It combines a dated training plan, recovery actions, food intake and meal plans, tracker workout recaps, a weekly reflection, and a small animal companion. Personalize it around the recipient’s own goal, whether that is a BJJ competition, another sporting event, or a defined training milestone.

The phone is the everyday interface. A backend on an always-on local computer provides durable shared storage and AI requests. An authenticated public HTTPS tunnel makes it accessible away from home and to the owner's external AI. The external AI can read the plan and logs, help with meals or shopping, and make explicitly requested changes through a model-facing tool interface such as MCP.

The app follows an existing plan; it is not an autonomous coach. Plan revisions happen through conversations with the owner's AI or coding sessions outside the app. No social feed, multi-athlete administration, technique journal, calorie reward for exercise, generic readiness score, or in-app shopping system.

### Current scope versus future ideas

Recreate now: Today, Week, Food, activity details, recovery steps, food interpretation/review/save, weight observations, weekly AI wrap-up, reminders, settings, durable offline records, authenticated synchronization, saved meal plans available to agents, tracker HR recaps and agent-readable workouts, and mascot moods.

Not part of the current baseline: natural-language manual workout interpretation, automatic workout categorization by an LLM, generated in-app workout narratives, sleep tracking, a full plan editor, pantry/leftover accounting, automatic shopping messages, advanced post-session arrival-relative notifications, or a containerized hosting setup. Natural-language manual workout logging was explicitly removed from scope. Other deferred features may be proposed later; do not implement them simply because an early design implies them.

The original app has some incomplete future schedule/prescription data. A new owner's plan should be authored only through their confirmed horizon; do not copy gaps as a feature or silently repeat one phase forever.

## 2. Personalize before building

Ask the owner for the following in small groups. Record answers in a human-readable personal configuration. Distinguish confirmed facts, proposed defaults, and unresolved values. Use null for unknown quantities, not zero.

| Input | What to establish |
| --- | --- |
| Device and access | Phone platform, local computer, whether it stays awake, external AI clients, and who will maintain it. |
| Event | App name, event name and date, or an agreed alternate home heading if no event. |
| Time and units | Home time zone, week start, body-weight unit, food and nutrient units, clock format. |
| Training | Dated phases, weekly days/times, session intent, exercise prescriptions, rest days, and any confirmed durations. |
| Recovery | The actual actions the owner wants, timing, hydration action, and any already-established supplement details. Do not invent doses. |
| Nutrition | Owner-selected calorie/protein/carbohydrate/fat targets, preferences, restrictions, meal timing, and two optional reference meal days. |
| Weight | Whether to use it, observation day, known baseline/goal, and whether maintenance targets are established. |
| Trackers | Which sources supply workouts, HR, walks and weight; available permissions; how source sport labels map to the owner's activities. |
| AI | Preferred provider, a capable low-cost food model, a stronger weekly-reflection model, private provisioning method, and acceptable request allowances. |
| Visuals | Accent color, animal character, event clothing/accessories, and whether mildly judgy expressions are welcome. |
| Reminders | OS permission, quiet hours, and code/data-authored times/types. |

Do not ask the owner to choose databases, web servers, frameworks, or deployment products unless they have a preference. The building AI should inspect the available environment, select a maintainable stack, and explain its choice briefly.

Configure calorie tiers for planned recovery, short strength, one sport session and multiple sessions. Save each dated target with the schedule used to author it. Changes prompt agent review, never automatic calorie reductions or exercise eat-back. Configure a consecutive-day average weight threshold and a maintenance calorie increment; use one observed reading per local day, ignore future data, preserve missing days, and recompute after corrections/deletions. Maintain historical policy boundaries. Later weight increases do not automatically revert an established maintenance transition. These are planning estimates, not measured energy requirements.

If meal planning is requested, author concrete breakfast, lunch, dinner and recovery-snack examples using the owner's targets. Optionally alternate two reference days over the next confirmed week. Persist each dated plan; do not leave it as chat text. Keep generic food calculations and brand/preparation uncertainty separate from the user's actual intake.

## 3. Product rules that should survive personalization

1. Only three primary destinations: **Today / Week / Food**. Settings is secondary. There is no History or Week So Far tab.
2. All plan items have actual dates and times. Logging is allowed at any time. A late item is unknown, not automatically missed or skipped.
3. Planned work, performed activity, consumed food and imported tracker records are different concepts. Never turn one into another silently.
4. Exercise checkmarks commit Done with Undo; other quick-log icons open details. Tapping the row itself opens details without completing it.
5. Completion and skip are explicit, reversible outcomes. Record the actual outcome time separately from the scheduled time.
6. Missing information stays unknown. A missing food day is not zero intake; a missing HR interval is not rest.
7. Food interpretation creates an editable draft. Only explicit save creates or changes intake.
8. Use concise functional copy. No motivational slogans, explanatory filler, or implementation requirements disguised as interface labels.
9. Plan adjustments and shopping workflows live outside the app. The app shows the accepted plan and records observations.
10. Personal health/nutrition choices come from the owner. The building AI must not import another person's targets, doses, event, schedule, measurements or credentials.

## 4. Visual system and navigation

White background; near-black text; one clear accent color, blue by default; pale accent panels; fine neutral separators. Avoid a dense dashboard full of nested cards. Use a clean system sans-serif, strong numeric hierarchy, modest rounded corners and generous whitespace around primary actions.

Reference tokens: ink #10151E, secondary text #535F70, divider #D9E0E9, blue #2465DA, pale blue #EDF3FF. Typical hierarchy: 28–30 headline, 19 section title, 14–15 body, 11–12 supporting text in platform-scaled units. Adapt these to the platform and accessibility settings rather than hard-coding a fixed phone size. Interactive areas should be about 48 device-independent units or larger.

The mascot sits beside the countdown heading, not in a separate banner. Allocate roughly a quarter of the header width and enough height for the complete animal; do not shrink it into a barely visible icon. Use a custom animal with readable silhouettes and transparent backgrounds. A weasel in a martial-arts uniform is the source design, but the recipient should choose their own character.

Use category icons: martial arts for mat training, weights for strength, utensils for food, and recovery/rest imagery for recovery. Pending exercise: blue check action; pending other logs: blue pen. Done: filled blue check. Skipped: muted minus. Partial routine: distinct partial-state shape. Provide accessible names and state descriptions; color alone is insufficient. Do not print “Not logged” next to every row.

Details close with **×** and return to the originating screen/date. System back should behave consistently and preserve drafts. Log/Skip controls near the top of activity details should be balanced and centered, not stranded in one corner. Move is a small secondary action beside the time, not the main action. Food entry has its own action pattern below.

Support large text, scrolling, keyboard overlap, reduced motion and screen readers. Do not clip labels, charts or primary actions on narrow displays. At larger text sizes put the countdown above the complete mascot, giving the number full width; ordinary text keeps the side-by-side composition. Reserve a full touch target beside wrapping activity dates for Move, and wrap the detail action buttons when needed. Weight and heart-rate summary columns share the available width. Announce activity completion state and both the displayed Food date and the date control’s return-to-today action.

If a tracker detail cannot read its cached data, keep Close available and show a concrete instruction to refresh health access in Settings. Retry the read when the imported snapshot changes. An unsuccessful unlink must report failure without claiming the link was removed. These presentation and failure states never alter recorded completion or measurements.

## 5. Screen-by-screen specification

### Today

Heading: “91 days to [event]” using the real local-date difference, not “Today.” Agree on event-day/past-event wording rather than showing nonsensical negative countdowns. Place the mascot alongside it and a small settings affordance nearby.

Home omits the nutrition summary; the Food tab retains a blue consumed/target summary with its pencil entry action.

Next show **Today**, **Tomorrow**, or the scheduled weekday with concrete instructions for the next unresolved planned item, looking ahead after today is resolved. Morning means actions such as breakfast, hydration or stretching, not a sleep survey. If the item is a routine, show its independently loggable steps. Include enough detail to act without opening every row: food/portion instructions, exercise prescriptions or step durations where known. Exercise uses Done; other activities open logging details.

Follow with later unresolved items and their scheduled times. As items resolve, the next card advances. Scheduled rest still has its recovery actions; do not replace them with “no training planned.” A compact imported walking summary can provide daily movement context without awarding exercise calories or marking training complete.

### Week

Previous/next week controls; seven dated, expandable groups. Open the current date by default when viewing the current week. Show actual plan items, including rest, recovery children and the weekly weigh-in.

Collapsed groups summarize the plan and list imported workouts by name and a concise intensity word where enough evidence exists—for example, “BJJ · Moderate.” Do not substitute “1 tracker workout.” Expanded groups show scheduled rows and unlinked imported workouts. Each workout opens its measured recap. Once linked to a planned session, do not list/count it again as a separate unlinked workout.

Opening Week or returning from another tab must retain already loaded workout names and intensity labels. Read the locally persisted tracker snapshot without waiting for network sync; derive summaries once per changed snapshot off the UI thread, retain them across navigation, and update them when imports, corrections, deletions or workout links change. Do not reset the list to empty and rebuild every heart-rate recap on each visit. Cold launch may briefly load local data; tab switching should reuse it.

Time controls use 12-hour display with explicit AM/PM, including session Move, food-entry details, and reminder settings. Tapping the time opens a 12-hour picker; cancelling preserves the current value. Confirming selects the time in the current form; moving a session still requires its Move action, and food changes still require saving the entry. Store unambiguous local time and retain the date/time-zone rules, including midnight and noon.

Moving a session retains its identity and outcome history. Move unfinished attached recovery steps by the same intended offset once; preserve completed step timing. Do not invent additional sessions as debt.

Put **Weekly wrap-up** here, not on Today or a separate history screen.

### Food overview

Date controls, a prominent calorie/protein/carbohydrate/fat progress widget, and one right-aligned food-entry icon. No redundant “Food” or “Eaten so far” heading inside the screen.

Show recorded food entries for that day with useful totals and an edit path. Under **Keep eating**, show the remaining planned meals and their foods/portions. Include dinner. There is no log button beside every planned meal. A meal plan is a suggestion for later intake, not a consumed-food record. Use a versioned meal-template library with food/preparation state, gram portions and nutrient-source snapshots; compose dated meals around the accepted training schedule. Materialize dated records through the planning horizon so external agents can read and edit them. Only fill missing IDs; existing custom days and deletion tombstones win. Preserve stable meal IDs and meal-time exceptions. Extending the horizon never overwrites agent edits or logs food. Maintenance adds the configured increment to each accepted planned-day tier. Portion changes for maintenance remain an explicit meal-plan edit.

Swipe a planned meal in either direction to dismiss it from **Keep eating** for the selected date. The row disappears after the dismissal is saved; a brief “Breakfast dismissed” message offers **Undo**. An unobtrusive restore icon beside **Keep eating** remains available while any planned meals are dismissed and restores all dismissed meals for that date. Provide the equivalent named dismiss action to screen readers. The gesture must coexist with vertical scrolling. If saving fails, retain the row and show a short retryable error; if restoration fails, retain its prior state. With no meals left, show “No remaining meals planned.”

Dismissal is independent of food logging: it creates no intake, nutrient totals, completion/skip outcome, or plan edit, and never changes mascot mood. Preserve it across app restart and offline use, keyed by date and stable meal ID; tomorrow’s breakfast stays visible. Current dismissal state is device-local presentation state, so it is not shared through the backend/MCP but is included in the portable local-state backup. Food correction/deletion does not undo a manual dismissal.

Link an intake entry to a planned meal only when explicitly selected or preserved from the entry flow. Current behavior hides that linked meal from the remaining list; unlinked food does not guess which meal it fulfilled. Corrections/deletion must update the list and totals. Plan changes do not rewrite already consumed food.

No shopping list, pantry UI or meal-plan editor. Agents can read/update saved meal plans outside the app.

### Food entry and review

Top row: **×** left; **Add** right. A text area accepts ordinary food descriptions, including weights, brands, preparation and corrections. No top Log or Skip, no “include weights” subtitle, no “calculate calories and macros” label.

Add requests interpretation and presents one editable aggregate draft: the original description, known mass, calories and macros, plus a concise clarification only when necessary. The current product does not require a detailed per-ingredient ledger or a nutrition database integration. A model may use typical values when reasonable; supplied label values and measured quantities take precedence. Avoid repeating “estimated” as UI clutter; retain interpretation provenance internally.

Offer manual details/totals, date/time and optional planned-meal association. Blank nutrients stay unknown. Validate units and numeric ranges. Once reviewed, provide one final save-check action aligned right. Save a new entry once; editing an existing entry replaces it once. Support explicit delete with confirmation. Close, process restart, network failure and late AI responses must not lose text or unexpectedly save intake.

### Mat-training session and imported workout

Planned-session detail has Log/Skip near the top and secondary scheduled-time/Move controls. The main content is a measured tracker recap, not a technique form: duration, actual start/end, average/peak HR, HR curve, coverage, and compact zone bars with durations. No title saying “Estimated time in zones.” Missing intervals remain gaps; absent data does not produce a synthetic curve.

Provide explicit choose-workout/link/unlink for ambiguous matching. Linking does not fabricate completion. Do not track “first grip → takedown → top control,” tactics or technique notes. An imported-only recap does not need redundant plan completion controls.

A future manual text-entry flow can mirror food, but it is not implemented in the reference baseline; do not present it as working until built and tested.

### Strength session

Read-only prescribed exercises with available sets, reps, holds or loads; **Done / Skipped** controls. Done opens the three-choice session-feedback flow described below. No detailed set-by-set performance form. Unspecified loads or sets remain absent rather than invented. Show the saved plan without generic filler.

### Recovery and rest

A routine contains independently loggable steps. Each has its concrete action and known duration. Parent progress derives from the children: all done, all skipped, mixed/partial, or pending. One completed child does not complete the entire routine.

Hydration is an action such as “12–20 oz of water,” personalized to the owner's chosen instruction, with Log/Skip. Do not ask for a consumed amount or infer an exact volume from completion. Supplements, if enabled, require the owner's product and actual dose; do not invent or activate unspecified doses. No sleep form or ambiguous “evening times” screen.

### Weight and weekly wrap-up

Weight entry is an explicit observation with unit, time and optional context. Allow manual entry or selection of an eligible imported measured weight. Profile settings on a tracker are not measured body weight. Prevent duplicate imported selections. Support correction/deletion and a selected weekly observation.

Weekly wrap-up: graphical observed weight trend/delta, a coaching synthesis of what the logged week suggests about preparation for the configured goal, and read-only considerations for the next week. Put missing-day/logging coverage in a footnote, not throughout the summary. Show dates and gaps; do not manufacture trend points.

No “what would you change” form, Save Plan, Ask Follow-up, or chat interface. Suggestions never modify the plan or targets. The owner discusses changes with their external AI. Cache generated text for its input snapshot and regenerate when relevant data changes. A week without evidence should say so without calling a model to invent a narrative.

Home exposes an icon-only **Sync Health Connect** action beside the Settings gear, without an outline. It reads permitted tracker data, shows progress, prevents repeat taps during a read, and reports success or permission/failure guidance in a snackbar. Permission/source management and data removal remain in Settings. A refresh does not complete planned activities. Weekly wrap-up prose and its coverage footnote support native text selection and Copy.

### Settings and reminders

Reminders speak in the mascot's voice: conversational, specific, occasional animal puns or light teasing, without motivational filler. Deterministic code builds factual sentences from actual plans and explicit logs. An optional lightweight model selects an approved contextual joke; it cannot invent facts, timing or actions. Missing logs never become accusations of skipping. No weight/food shaming or pressure to train through injury. Cache against reminder identity, due time, destination and factual context. Prefetch only upcoming copy; delivery never waits for the network and uses the complete local fact sentences on failure or budget exhaustion.

Settings groups: synchronization, tracker access/refresh, notification preferences, goal information, nutrition targets, and data export/restore. Keep connection diagnostics secondary to ordinary use. Separate Disconnect, clear local tracker cache, and delete a real record.

Reminders are opt-in, with the existing user's preferences preserved during upgrades. Defaults: morning overview at 8:00 AM, training preparation 60 minutes before a session, recovery 15 minutes after a linked tracker's actual end (otherwise only after an explicitly configured duration), and a single food check-in at 7:30 PM only if no food entries are saved for that local date. Sunday review at 9:00 AM replaces ordinary morning; a saved Sunday weight changes the message to wrap-up. Morning includes concrete planned meals, training times and recovery steps on rest days. Food mentions only currently visible meal suggestions, without asserting that they were not eaten. Explicit prior-day skip references disappear after subsequent completion. No per-step or per-meal nagging.

Open routes to Today, the session/recovery detail, food entry or weekly review. Snooze 30 minutes is a notification action; a second action opens 10/30/60 minutes and a custom AM/PM picker. An earlier chosen clock time means tomorrow, within 24 hours. Reject quiet-hour snoozes with an explanation. Daily overview, food and Sunday-review snoozes stay on their original date; the chooser rejects a next-day time for these. Training and recovery snoozes may cross midnight within 24 hours, retaining the original activity. Dismiss closes only the notification; no action silently records completion or changes scheduled activities. Announce action labels for accessibility and keep the useful fact first in collapsed notifications.

Keep one alarm per stable reminder identity; persist scheduling/snoozes locally. Reconcile moves, completion, tracker-link changes, meal dismissals, reboot and clock changes. Re-read eligibility and facts at delivery. Cancel already-visible notifications when facts change; do not re-notify just because text changed. Quiet-hour non-overview prompts are dropped, while the next overview describes current plans. Shift the overview to quiet-hours end only within the same date. Drop very late callbacks rather than producing catch-up bursts. Schedule a rolling horizon and replenish after delivery and synchronization. Permission denial leaves in-app tasks available; platform delivery is approximate. Dated prep activities support local notifications. External shopping/message delivery remains disabled until configured.

## 6. Architecture without a prescribed stack

### System diagram

```mermaid
flowchart TB
    subgraph phone["Phone"]
        mobile["Mobile app: Today / Week / Food"]
        cache["Local records, drafts and durable outbox"]
        health["Permissioned tracker / platform health data"]
        mobile <--> cache
        health -->|"Tracker observations"| mobile
        mobile -->|"Opt-in saved food logs, edits and deletions"| health
    end
    agent["Owner's external AI: own LLM + MCP tool client"]
    tunnel["Authenticated public HTTPS tunnel"]
    mobile <-->|"Sync, tracker upload, app AI requests"| tunnel
    agent <-->|"MCP tool calls and results"| tunnel
    subgraph host["Always-on local computer"]
        api["Mobile app API"]
        mcp["MCP server interface"]
        rules["Shared validation, revisions and limits"]
        db[("Canonical records and usage ledger")]
        secrets["App model-provider credential"]
        api <--> rules
        mcp <--> rules
        rules <--> db
        secrets -.-> rules
    end
    tunnel <--> api
    tunnel <--> mcp
    models["App's model provider: food drafts + weekly reflections"]
    rules <-->|"Selected inputs / structured proposals"| models
```

**The mobile API and MCP server are two interfaces to the same backend, not two independent databases.** The mobile app reads cached data and queues offline changes; the backend accepts canonical revisions. Tracker data enters through the phone and selected records are uploaded.

**There are two AI roles.** The app calls its configured models through the backend for food drafts and weekly summaries. Separately, the owner chats with their own AI, whose tool client calls MCP to read or explicitly update records. Those AI roles can use different providers. MCP exposes tools; it is not itself an LLM.

**Saving is explicit.** Model responses do not write to the database. Food requires review/save, weekly text remains read-only, and external edits use authorized revision-checked commands. The provider credential remains on the local computer; the phone and external AI use separate app/agent credentials. The public tunnel provides reachability and HTTPS, while backend authentication still controls access.

Logical flow:

Owner ↔ phone UI ↔ durable local records/drafts/outbox ↔ authenticated HTTPS tunnel ↔ local-computer backend ↔ persistent database.

External AI ↔ authenticated model-facing tools ↔ the same backend/domain operations.

Backend ↔ selected model provider for food drafts and weekly reflections. Phone ↔ platform health-data interface for permitted tracker imports.

Choose native, cross-platform or another mobile approach according to reliable local storage, health access, background sync and notifications—not appearance alone. A browser-only build must not claim native health ingestion or background delivery it cannot support. If a platform requires a bridge, document it before promising parity.

Backend runs on a local computer reachable through a public HTTPS tunnel. It owns canonical accepted revisions, domain validation, model credentials and tools. Choose the hosting/process manager, database, proxy/tunnel and isolation approach to fit the owner's environment. Preserve stable public addresses through deployments. Document startup, machine sleep/login dependencies, backup, update and rollback. No hosting brand is required.

The phone works offline for viewing cached plans, manual logging, corrections and drafts. Model requests need a configured connection unless a tested local model meets the quality target. Never pretend the fallback is an AI interpretation when it is a manual form.

### Logical records

| Concept | Minimum responsibility |
| --- | --- |
| Personal configuration | Event, time zone, units, targets, selected sources, confirmed mapping rules and preferences. |
| Plan revision/template | Versioned prescriptions and phase scope; reusable training and recovery definitions. |
| Scheduled activity | Stable ID, kind, title, timestamp, prescription snapshot, parent/link IDs and explicit outcome/time. |
| Recovery child | Stable parent occurrence identity, action type, offset semantics, own outcome and actual intake only when supplied. |
| Food entry | Stable ID, description, actual time/local date, nullable nutrients/mass, optional meal link and interpretation provenance. |
| Dated meal plan | Planned foods/portions/times/targets, separate from intake; revisioned and agent-editable. |
| Weight entry | Actual observation, unit, source identity, date/context and optional weekly selection. |
| Imported source record | Provider/record identity, original type and values, timestamps/offsets, correction metadata and tombstone. |
| Tracker snapshot | Selected workout, same-source samples, revision, observation time and explicit plan link. |
| Command/outbox | Unique command ID, entity ID, expected revision, operation and durable payload. |
| Draft/wrap-up cache | User draft or generated result with source/input fingerprint; separate from saved facts. |
| Notification/usage state | Delivery identity/snooze state; model usage reservations without private prompt content. |

A typed plan framework composes these records. Do not implement a single opaque blob of disconnected UI entries. Keep definitions, dated occurrences, observations and derived summaries separate even if the chosen storage format is flexible.

### Synchronization invariants

- Stable IDs and unique command IDs; retrying exactly the same command has exactly one effect.
- Expected-revision checks; stale changes conflict rather than silently overwrite.
- Ordered durable local writes, accepted server revisions, incremental change cursor and deletion tombstones.
- Drafts survive restart; stale edits cannot quietly replace a newer accepted record.
- Synchronize on foreground entry, new pending work and a platform-appropriate periodic network opportunity. Do not promise exact background timing.
- Display connection freshness, pending work and actionable errors in Settings. Preserve data during disconnection or reconnect.
- Source-workout publication uses separate durable envelopes/checkpoints. A tracker error must not block ordinary food/activity synchronization.
- Clearing a local import cache is not a source deletion. Reconcile deletions only inside a fully and successfully read source interval; retain outside-window history.
- Separate scheduled wall time from actual instants. Define home-zone versus travel behavior explicitly; handle daylight-saving gaps/repeated times and midnight recovery steps. A new app can use the configured home zone as its default schedule zone.
- Never uninstall/clear personal data as a deployment or conflict-resolution shortcut.

## 7. Tracker analytics and interpretation

Import only permitted categories, preserve source identity, and choose the owner's trusted source for each purpose. Workouts and their HR samples must come from the selected source and lie within the actual session interval. Treat ordinary walks as movement/recovery context, not extra mat sessions or food credits.

Map sport labels explicitly. For example, an owner might record BJJ under a Judo/martial-arts profile; only apply that mapping after they confirm it. Preserve the original type/title and the mapping provenance. Do not label every workout from the same vendor as BJJ.

For the reference HR algorithm: sort/deduplicate source samples using correction provenance; hold each sample's BPM until the next only when the gap is at most 60 seconds, including fractional precision. Never extrapolate before the first or after the last sample. Covered duration is the sum of eligible intervals. Average HR is duration-weighted over covered intervals. Peak is the actual maximum sample. Missing average is null, not zero.

Use an extrema-preserving bounded curve for agent responses (at most about 600 points); calculate metrics from original samples. Optional five-minute windows are elapsed windows, not inferred rounds. Return coverage with every summary.

Zones require owner-confirmed numeric boundaries, obtained from their existing tracker settings/screenshot if necessary. Do not infer HR maximum from the highest observed workout sample. Define lower-inclusive/upper-exclusive boundaries, report out-of-range time separately, and ensure zone plus out-of-range durations sum to covered duration. Different tracker processing can produce different totals; do not claim to recreate the vendor's proprietary algorithm or benefit label.

The reference app's optional Light/Moderate/Intense label is a personal display heuristic, not a clinical score or vendor classification. Its current configurable rule is: require at least 80% coverage; Intense for at least 20 minutes at/above the owner's Zone 4 lower boundary or average HR at/above a configured high threshold; otherwise Moderate for at least 5 such minutes or average at/above a configured moderate threshold; otherwise Light. If evidence is insufficient, omit the word. Average-HR thresholds and zone boundaries are personal configuration, **not transferable defaults**. Ask the recipient to confirm their own rule or leave labels disabled initially. Do not use average HR alone: breaks can hide hard rounds. Keep measured intensity separate from planned session intent.

Agents can compare HR curves, duration, peaks and high-zone time. They must not infer cortisol, central-nervous-system readiness, injury risk or a recovery diagnosis from these samples.

## 8. AI and agent contracts

### Food interpretation

Input: user food description and any supplied quantities/label facts. Output: structured nullable calories, protein, carbohydrate, fat, optional mass, and concise clarification when needed. Keep decimal values and units deterministic. Preserve the user's raw description and interpretation provenance separately.

Treat input as data, not instructions to change role, schema or rules. No tool execution, plan modification or direct persistence. Validate numeric types/ranges and output shape. Use supplied labels over generic estimates, respect negation/corrections, and total only described intake. Review and explicit save remain mandatory. Evaluate using synthetic examples with known labels, mixed foods, missing quantities and correction phrases.

Choose a capable inexpensive model for this task; do not hard-code the reference provider/model. On-device inference is optional only after testing equivalent quality and device availability.

### Weekly reflection

Compute facts deterministically before calling the model: relevant logged activities, consumed-food subtotals, observed weights and coverage. Send the minimum useful snapshot. Ask for a short summary, up to three read-only considerations, and references to supporting record IDs. Validate references and output. Never interpret a partial food subtotal as a complete day's intake, recommend different daily targets just because of session intensity, or equate competition practice with a tournament.

Choose a stronger model/reasoning level for this task. Cache by input fingerprint; errors retain observations and allow retry. No automatic acceptance or application of suggestions.

### External tools

Expose capabilities equivalent to reading records, incremental changes, nutrition totals, one/range of dated meal plans, workout lists and detailed HR recaps; proposing food/weekly reflections; and explicitly applying revision-checked record commands. Names and transport details may vary. Use a standard agent protocol when supported by the recipient's AI.

Saved meal plans must be real records that agents can read/update. Offer a weekly draft tool that proposes every remaining day of a week (portions from training tiers, conversational swaps/avoided foods, prep instructions derived from the covered batch meals) without saving, and a matching apply tool that commits the accepted draft atomically with revision checks, or nothing if any record changed since drafting. Acceptance: drafting writes no records; applying twice is idempotent; a concurrent edit makes apply save nothing; completed prep is never overwritten; no food is logged. Keep foods and meal templates in an agent-editable, revisioned catalog on the backend rather than in code: per-100 g estimates, a balancing role per food, starting gram amounts per template, and entries the owner can switch off or add in conversation. Balance each drafted day toward the protein/carbohydrate/fat targets within sensible portion bounds, and snapshot the food values into each saved plan item and the template's batch/use-up flags into each saved meal. Validate structured items (food identity, positive grams), since prep and shopping compute from them. Keep meal planning ignorant of why a day's targets are what they are: drafting asks the nutrition policy for the date's targets and balances one set of meals to them. A weight-phase change (such as starting maintenance) changes targets only; redraft the remaining days rather than storing alternative portions. Acceptance: catalog edits never change already saved plans, including which meals count as batch or use-up; a plan with an item lacking grams is rejected at save; restarting never overwrites edits; drafted days land within a few grams of each macro target when the catalog contains adequate fat and carbohydrate sources. Agent edits propagate to the phone. External shopping suggestions do not log food or deduct a pantry automatically. Tools that write require explicit user intent in the caller's workflow, but textual tool descriptions are not server authorization controls.

A read-only grocery tool can aggregate an applied week into purchase-form estimates using explicit cooked-yield metadata. Report the result as a practical shopping list: give each catalog food an optional purchase unit (a singular/plural name and the bought grams one unit contributes, e.g. one egg at 50 g or a 1 kg bag of rice) and convert the aggregated grams into whole units, rounding up so no line under-buys, while still showing the grams needed. Where a unit is only sold in multiples, record the pack (a dozen eggs, an 8-pack of tortillas) and round counts up to whole packs, reporting both the unit count and the pack count. Foods sold loose by weight carry no unit and stay a weight-only line rather than a guessed count. Say that pack sizes are typical rather than store-verified and that rounding up leaves leftovers. Acceptance: unit counts never fall short of the needed grams and are always whole multiples of a declared pack; a food without a declared unit never reports a count. Let the owner flag shelf-stable pantry staples (oil, rice, oats and similar); list the staples a week uses under a separate "check stock — may need replenishment" section with no amount, since one package outlasts many weeks. Acceptance: a staple never appears with a quantity and never as an unresolved conversion. Show missing dates and unresolved conversions; do not infer pantry stock, log consumption or place orders. Use-up meals are excluded from purchasing. Keep this outside the app’s meal/logging UI. Report text-only meals and unknown conversions as unresolved with date, meal identity and original food descriptions; do not silently omit these meals. Return a visibly partial shopping list when unresolved entries remain. Acceptance: shopping quantities do not change when the weight phase changes; a draft made after maintenance begins lands on the higher targets with one set of meals; legacy meals are visible as unresolved; saved records remain unchanged. Daily protein is a personalized configuration; an owner’s target change must keep source/docs aligned without silently rewriting historical food or plan snapshots.

The personal reference deployment deliberately grants its owner-controlled agent broad access. Separate app/agent credentials are required; fine-grained scopes are optional according to trust and intended sharing. State clearly what each credential can do. Never embed provider keys in the phone or publish them in this document.

### Resource and request bounds

Apply body-size bounds before parsing on **both** phone and agent transports. Apply the same canonical command bound in shared domain validation, not just one HTTP route. Reference allowances: 256 KiB commands; 1 MiB agent/weekly payloads; 4 MiB bounded tracker uploads; approximately 40 KB food-interpretation request. Limits are implementation policy, adjustable after measured needs; no route may bypass them.

Share inference allowances across clients: reference baseline is two concurrent calls, six starts per rolling minute, 50 food calls and 20 weekly calls per local calendar day. Persist reservations so restarting does not reset the counters. Failures/cancellations that reach the provider count because billing may already occur. Reject invalid input before reserving. Never store prompt text in usage counters. These request limits plus input/output caps limit exposure; they are not an exact monetary budget. Also configure provider-side spending controls where available. Use no automatic paid retries and finite time/output limits.

## 9. Animal companion

Six visible poses: idle, pleased, mildly judgy, training, recovery and rest. The normal contextual pose follows the next planned activity. The current implementation uses transparent sprite art with subtle breathing/sway/nod transforms, not a fully articulated animation engine.

Default disposition is normal. Two explicitly skipped top-level training/recovery sessions within the last three local calendar days may trigger judgy, if the owner wants that behavior. A complete recovery routine counts once; a few skipped children do not each count as a skipped session. A later completed training/recovery session clears the earlier skipped run. Recompute from current records after edits or deletions. Pending items, elapsed time, missing logs and planned rest never count as failure.

A new local activity completion or newly saved food/weight entry triggers a brief pleased reaction, about three seconds, without regard to intake amount or weight direction. If details are open, it can wait briefly for the return home. Do not replay reactions on startup, synchronization, correction or duplicate save. Judgy overrides normal context; transient pleased overrides both. Respect reduced motion. No mood caption, pet-care chores or rewards for dieting harder/extra exercise.

Deliver the selected animal art, pose sheet and matching app icon as editable/reusable project assets with documented generation prompts or source ownership. Do not require the recipient to reuse the original owner's character.

## 10. Deployment, security and recovery

### Security overview: protecting the model-provider key

The valuable secret is the credential that lets the backend spend money with the model provider. **Keep it on the local backend computer only**: never bundle it in the mobile app, give it to the external AI/MCP client, insert it into a prompt, or include it in logs, exports, source control or shared documents. Provision it through a private secret file or equivalent secret store accessible only to the service. The backend sends it only to the configured provider over HTTPS; callers cannot supply a destination URL that would redirect the key elsewhere.

The phone and external AI instead hold **separate, revocable app/agent credentials**. Those credentials authenticate requests to this app; they do not reveal the provider key. Public access uses HTTPS, phone enrollment is encrypted, redirects must not forward credentials, and server/provider errors must not echo secrets. App-invoked food/review models receive selected task data, not credentials, and have no tools or direct backend-file access. The external AI has only the separately authorized MCP capabilities. Prompts constrain interpretation, but keeping secrets out of model inputs is the actual boundary.

A stolen client credential could still request paid inference through the backend. Shared, persistent rate/daily allowances, bounded concurrency, limited input/output sizes, fixed server-selected models and no automatic retries constrain that abuse. They are request limits, not a guaranteed dollar cap; add provider-side spending controls or alerts where supported. Rotate the affected client credential if it is exposed; rotate the provider key if the server or key itself is exposed.

**Residual risk:** compromise of the backend account/host can expose the provider key and stored records. File permissions, service isolation, host updates, encrypted backups and disk encryption reduce different parts of that risk. Disk encryption protects offline access to a lost/stolen computer; it does not stop an attacker already executing as the running service. These controls mitigate key theft and spending abuse; they do not make either impossible.

Use authenticated HTTPS for public phone and agent access. Backend should bind privately behind the tunnel/proxy. Reject unintended hosts/origins where applicable. Use distinct random credentials, safe secret storage, encrypted phone enrollment and platform backup exclusions. Disable credential logging and arbitrary provider error-body display. Normal successful health responses should not be publicly cached.

A local always-on computer is the reference deployment model, not a requirement for a specific server product. Use process isolation appropriate to personal tooling; document residual shared-host trust. Containerization is a useful option, not a prerequisite or a claim about the original deployment. Device storage encryption, host disk encryption and runtime isolation address different risks.

For a new deployment, provide consistent database backups including pending transactional state where relevant, encrypted off-host copies, retention and a tested restore procedure. These are handoff recommendations: the reference deployment has deployment-time backups, but scheduled encrypted off-host backup coverage is not established. Distinguish additive user export/import from full disaster recovery. The phone exports a versioned archive with records (including tombstones), an audit copy of pending commands, drafts, dated meal dismissals, and quiet-hour preferences. Tracker records and their activity links are included only when the user explicitly selects that export option. The archive is readable personal health data: store it privately. Provider keys, app credentials, device enrollment, sync cursors, grants and scheduled platform alarms are excluded.

Restore opens a document picker, validates the version, units, record relationships and local-state allowlist, then shows record/local-item counts and whether a tracker snapshot is present before confirmation. Cancel changes nothing. Confirm performs an atomic additive merge: existing IDs, tombstones, drafts, preferences, tracker cache and pending writes remain unchanged. Missing live records enter the normal durable write queue using current domain rules; archived commands are never blindly replayed. Missing local state restores without generating food or training logs. A tracker snapshot restores only if no local tracker cache exists; it never silently replaces a newer cache. Restoring the same archive twice is idempotent. Invalid input leaves all data unchanged and shows an error; successful feedback reports restored and preserved counts.

Restored reminder settings retain quiet hours but start disabled; the owner explicitly re-enables reminders after granting notification access. A new phone must enroll securely again and re-grant tracker access. Legacy record-only archives remain readable, but their unvalidated extra metadata is ignored. This is a portable additive recovery path, not a full server restore or an automatic resolution of conflicts. Generated weekly summaries can be regenerated from restored/synced records; cached summaries, tracker publisher checkpoints, snoozes and enrollment are not migrated. Server disaster recovery uses a separate tested database restore procedure: obtain a consistent snapshot while production continues, retain the original privately, restore a disposable working copy with separate credentials and provider calls disabled, verify all original records/tombstones/change cursors/command receipts/tracker rows/saved recaps, exercise authenticated app/tool reads and a retry-safe synthetic write only in the copy, then restart and verify again. Compare complete table rows, not just visible record counts. Never swap production routing or overwrite newer user data as a side effect of a rehearsal. Retain a verified copy away from the deployment host. The reference implementation passed this bounded rehearsal; scheduled backup coverage and whole-host recovery remain separate operational checks.

Before an update, preserve personal data. Test migrations against a copy, deploy code separately from real-plan changes, and verify auth, read paths and existing counts without creating synthetic production logs. Keep a rollback path that preserves writes accepted after deployment. The finished project must document build, install, server startup, tunnel, secret provisioning, backup, restore and update procedures for its chosen technology.

## 11. Build recipe for your AI

Paste this instruction with the complete document:

> Build a private personal training companion for my specific goal or competition from this blueprint. Treat the product behavior as the contract and choose the technology yourself after inspecting my device and hosting environment. Start with the personalization questions, capture confirmed configuration and unresolved values, then propose a concise architecture and screen plan. Do not import the source owner's data or choose health targets for me. Build and verify working increments rather than stopping at a prototype. Keep plans, drafts, observations and tracker data separate. Use meaningful unit tests liberally, plus integration and device checks. Preserve my data across updates. Do not silently add deferred features or deploy to an unrelated account. Never paste secrets into source or reports. At the end, deliver source, configuration, assets, setup/update/backup instructions, test evidence and a truthful list of remaining limitations.

Recommended implementation sequence:

1. **Personal contract:** confirm device/host, identity, schedule, targets, units, sources, mascot and privacy choices. Mark unanswered inputs. Define the confirmed schedule horizon.
2. **Visual contract:** render Today, Week, Food, food entry/review, tracker detail, recovery/strength, weekly wrap-up and Settings using synthetic fixtures. Check the copy/action rules before adding infrastructure. Use this document's layouts to avoid reopening settled product decisions.
3. **Durable core:** implement typed records, reusable plan/protocol composition, actual/scheduled times, explicit status, drafts and stable identities. Prove restart and correction behavior.
4. **Shared service:** implement canonical commands, auth, revisions, idempotency, tombstones, incremental sync and durable outbox. Add pre-parser limits and usage allowances before exposing inference.
5. **Everyday flows:** build Today, Week, food/manual review/save, weight, recovery and reminders. No synthetic data may appear as the owner's recorded history.
6. **Tracker slice:** prove one real permitted source import on the target device; implement correction/deletion windows, explicit matching, HR analytics and source-owned publication. Verify against source observations and known synthetic curves.
7. **AI last:** food proposals first, then evidence-grounded weekly text and external agent tools. Test prompt-injection-shaped input as data and retain explicit save boundaries.
8. **Mascot and polish:** integrate character poses, deterministic mood rules, reduced motion, icon and accessible status handling.
9. **Deploy and validate:** establish public authenticated access from outside the home network, verify backup/restore and client enrollment, install without data loss, then document handoff and known gaps.

Do not make every phase a mandatory approval interruption. Continue ordinary reversible implementation after the owner authorizes building; ask when a missing personal decision would materially change behavior or when a consequential external action lacks authorization.

## 12. Acceptance tests and handoff

Use a fixed clock, explicit time zone, synthetic provider responses and isolated test databases. Keep live personal records out of test fixtures.

| Area | Required evidence |
| --- | --- |
| Navigation/copy | Three tabs, countdown header, no redundant food titles, no filler, consistent quick-log and centered detail actions. |
| Scheduling | Every occurrence dated; early/late logging allowed; no inferred skip; moves preserve IDs/history and shift only unfinished recovery steps once. |
| Time | DST gaps/repeated times, midnight routines, week boundaries and event countdown tested. |
| Food | Label-based quantities, negation/corrections, unknown nutrients, failed interpretation, restart/close, double-save, edit/delete and meal association behave correctly. |
| Meal dismissal | Either-direction swipe; accessible dismiss; Undo one meal; restore all for selected date; restart/offline persistence; other dates unaffected; failed save retains row; totals, plans, completion and sync queue unchanged. |
| Nutrition | Today and Food agree; target ranges retained; explicit training moves recalculate both affected date targets; plans never count as intake. |
| Recovery | Mixed child outcomes derive correct parent status; hydration is action-only; no invented doses. |
| Tracking | Reimport/correction/deletion idempotent; same-source samples only; incomplete reads preserve history; no duplicate linked workout or calorie credit from walks. |
| HR | Known synthetic curve, fractional 60-second gaps, single/empty samples, exact zone boundaries, out-of-range values and coverage arithmetic tested. |
| Intensity | Hard rounds are not hidden by a low average; unknown/insufficient coverage omits the label; thresholds remain personalized display rules. |
| Sync | Offline edits survive restart; duplicate commands have one effect; stale writes conflict; tombstones do not resurrect; tracker errors do not block manual logs. |
| Mascot | Missing logs never judge; routine counted once; later completion clears judgment; food amount/weight direction ignored; no replay after sync/restart. |
| Weekly AI | No-data week, partial food coverage, observed weights only, valid evidence IDs, input-change cache invalidation and no automatic plan changes. |
| Limits/auth | Wrong/missing/cross-surface credentials denied; API and agent oversized writes both rejected; shared quotas survive restart; cancelled/failed calls release concurrency. |
| Reminders | Sunday replacement; explicit skip versus unknown; later completion suppresses teasing; missing end suppresses recovery; tracker end precedence; meal dismissal changes copy; quiet-hour coalescing; custom AM/PM snooze; moves/cancellation/reboot; stale-copy rejection; budget/offline fallback; no duplicate or silent logs. |
| Data/release | Portable state round-trip; duplicate restore idempotence; existing drafts/cache/IDs preserved; malformed archive rollback; optional tracker privacy; credentials excluded; reminder alarms not replayed; legacy compatibility; backup restore tested separately; upgrade preserves records/drafts; no credentials in exports/artifacts; no synthetic production logs. |
| Accessibility | Narrow screen, large text, screen-reader states, keyboard, back/close and reduced motion checked on the actual device. |

Deliver a runnable project, personalized configuration, reusable design assets, an implementation status document, test results, deployment and recovery instructions, agent connection instructions, and a short list of genuinely deferred features. Disclose any platform limitation instead of substituting a mock and calling it complete.

### Approved strength direction (schedule update pending)

Choose the strength-session source with the recipient: saved prescribed exercises or an external workout app. Preserve whichever instructions are actually saved on each dated session; do not assume an external app replaces an existing routine. Display available exercises, purpose and duration with Done/Skipped and lightweight session feedback. No detailed exercise/set/load logger or required direct integration. Schedule changes require an accepted revision.

### Next meal on Home

Below the next activity, show a next-meal card with the planned meal name, 12-hour time, foods and portions. Use the same remaining-meal rules as Food, including manual dismissals and explicit existing links. Prefer the earliest remaining scheduled meal at or after the current local time; if none remain upcoming, show the first remaining plan item. Hide the card when there are no remaining meals. Food entry remains on the Food tab; the Home next-meal card has no completion or food-entry action.

### Reminder copy implementation

Deterministic code builds factual conversational sentences and owns eligibility/timing. A low-cost model selects an optional joke from an approved context-specific catalog; unsupported skip jokes cannot be selected. This is constrained variation, not free-form model-written factual prose. Cache against the complete context and copy version; never make a network/model call during notification delivery. The backend bounds requests and daily spending; device failures back off and use factual template copy.

### Activity date heading and meal water prompts

The home activity card is titled Today, Tomorrow, or the scheduled weekday. Once today's activities are resolved, show the next unresolved future activity; do not call an unlogged earlier activity a missed task. Keep the meal card titled Next meal.

Each planned meal includes the configured water action (the source owner's choice is 12–20 oz of water); this is a reminder, not a measured consumption record. It appears in meal details and the next-meal card without changing nutrient totals. With notifications enabled, remind at each remaining meal's planned time. Combine with a morning/food/Sunday notification at exactly the same time instead of duplicating alerts. Dismissing or explicitly fulfilling the meal cancels its water prompt. Respect quiet hours; don't invent a time for untimed meals. Meal notification Open leads to the food overview, not an amount form. No water volume logger, automatic food entry or hydration completion is created. Existing recovery hydration remains its own explicit action.


### Weekly coaching synthesis — October 18

The weekly wrap-up interprets the logged week in relation to the configured competition goal, rather than listing completed activities. Lead with the most useful implication, explain its evidence and tradeoff, and offer up to three concrete considerations with reasons. Connect training mix, spacing and explicit recovery observations when supported; completion does not prove fitness, skill gains, fatigue or readiness. Keep coverage gaps in the footnote and do not compare partial food totals to daily targets. Sparse evidence warrants a shorter synthesis, not invented conclusions. Goal/date and strength preferences are recipient configuration, not universal prescriptions.

Generate creates a read-only result; Refresh wrap-up requests a replacement while preserving the displayed valid result on failure. Retry is available after failure. No plan changes, chat or additional logging form. Cache format changes invalidate older recap-style results. Current inputs include activity categories/completion, recovery steps, recorded food subtotals and weights; raw tracker HR, subjective effort and prior-week training comparisons are not yet included.

## Updated meal, prep and notification contract

Exercise rows have one-tap Done with Undo; the full row opens details. Use soft blue rounded tap areas of at least 44 dp, a check for exercise, pencil for other logs, filled blue completion and muted minus for skipped. Completion never fabricates an actual workout interval or HR. Undo restores only the unchanged completion it just created; conflicting edits are preserved with an actionable error. Home retains the complete mascot, countdown, dated next activities, Next meal and later items. No on-screen snooze links. Food retains its blue actual/target summary and pencil; no meal alternatives or assumed fridge/prep status in the app.

A prep activity is reachable from Today, Week and its native notification. Its detail shows actual scheduled time, a secondary Move action, instructions and covered meal dates. Done/Skip records preparation only. Agent meal templates offer familiar meals and small batches; finite use-up ingredients must not recur indefinitely. Availability remains user-reported; no silent inventory ledger. Read planning context and current revisions, preview totals without writes, then save authorized dated replacements through the canonical command interface. Retain current fields, stable meal IDs and consumed records. Missing quantities remain unresolved; versioned generic estimates are distinct from actual labels. Beyond the confirmed schedule, flag provisional assumptions internally and surface them in agent review.

Reminder types/times are code/data configuration, not a category-checkbox UI. Native notifications open prep, food, recovery or weight details; no reminder dashboard. Morning weighing prompts only without today’s observed reading. Re-check scale data when background access is available. Bedtime recovery includes only unfinished steps whose associated workout has ended or is done. Completed/explicitly skipped items suppress reminders; unknown does not mean skipped. Meal notifications carry foods and portions plus a water action. Snooze stays on notifications. Settings retain OS notification/background-read permission controls and quiet hours. Permission denial leaves all manual flows usable.

Health ingestion has a separate periodic background worker and foreground refresh, independent of backend network sync. Availability and granted background-read permissions gate reads. Imported observed scale measurements have stable source IDs, original values and correction/deletion handling; manual overrides and tombstones survive refresh. Profile weights never become observations. Refresh failure retains prior data and exposes a retry/access message in Settings. Platform and upstream delivery are approximate.

Acceptance: six daily weights do not transition; seven qualifying consecutive days do; seven same-day measurements do not; missing days/future readings are excluded; earliest daily reading is deterministic; later gain does not revert; corrections can remove qualification. Verify app/backend targets match, explicit training moves recalculate both date targets, without changing consumption or portions, no logs are created by planning, prep never consumes food, no duplicate alarms after reboot, weight reminder suppressed by imported observation, and completed recovery cancels bedtime reminders. Verify these against isolated data before any personal-record deployment.

### Simple rest-day recovery
A recipient may choose one short gentle-stretching activity on confirmed rest days. Use the actual activity name and duration in the row, detail and bedtime reminder. Store the chosen application/instructions and scheduled local time in the prescription snapshot; do not launch a session or infer completion. Detail offers Done/Skip; completed/skipped suppress bedtime reminders. Missing logs remain pending, never automatic failure. A future-plan migration removes only pending superseded records through revision-checked tombstones, preserves logs and refuses to place rest-day routines on authored workout dates. Do not extend workouts based solely on provisional nutrition planning. Sync changes through the same canonical API/outbox flow; conflicts require re-reading, and offline views retain the last accepted plan. Accessible labels name the activity and state. Verify no duplicate routines, preserved history, correct local timezone across daylight saving and cancellation of old alarms after synchronization.
Native reminder notifications use a transparent monochrome mascot-head small icon, recognizable at status-bar size. Android supplies its tint; the app's colored mascot stays in the app.

## Native polish and memory review — October 30

This is the latest implemented review direction, still subject to visual feedback. The main agenda uses date → prominent countdown with a complete personalized mascot → Today/Tomorrow/weekday → flat activity row → Next meal → later activities. Use typography and consistent spacing to establish hierarchy. Avoid a large colored container around a simple activity or repeated instruction paragraphs on the agenda. Show full existing prescriptions only after opening the activity. Keep meal names, times, portions and water in a quiet neutral panel. Keep the blue consumed/target summary on Food. Use a native bottom navigation indicator behind the selected icon, with text labels retained for the three destinations.

- **Activity interactions:** the row opens details; exercise checkmark completes once with Undo; other activity pencils open the relevant logging flow. Completed controls are filled blue; skipped controls use a muted minus. Unknown remains unresolved. Icons have accessible action and item labels and at least 48 dp touch areas. Use a calendar-edit icon beside the time for rescheduling; it never marks completion.
- **Move state machine:** initialize draft date/time from the selected activity; Today/Tomorrow shortcuts update only the draft. The calendar and AM/PM picker update the same draft. Move commits through the existing revision-checked operation, preserving identity and history; disable repeat commit while busy. The × close control, outside tap and Back dismiss the dialog without writing. For BJJ and weights, a separate “Cancel activity” action marks the existing activity skipped through the normal durable command queue, without applying unsaved date/time selections, changing identity, deleting records or reducing the nutrition budget. Disable all actions and dismissal during the write; keep errors and draft selections visible on failure. Errors remain visible without discarding selections. Moving training recalculates budgets on both affected local dates without requiring meal review. Skipping alone never reduces a budget.
- **Dates and food:** Week/Food have one date navigation row, with accessible previous/next controls. Tapping the week range returns to the current week; tapping the food date returns to today. Food entry starts with its date, close, input and right-aligned Add; omit a repeated Log food heading. Optional Details uses the calendar and time picker. Interpretation is still an editable draft, and the final check is the only consumption commit. Preserve drafts on close/failure. Planned meal rows omit repeated macro totals.
- **Notifications:** retain native platform layout and its accessible action text. Tapping the body opens the destination. Exactly one Snooze action opens a chooser with 10/30/60-minute chips, optional custom time, one Snooze confirmation and Cancel. Selection alone never schedules. Busy state blocks repeat confirmation; errors remain in the chooser. Keep quiet-hour, expiry, completion and stale-delivery checks. OS presentation may differ between phone versions; do not promise icon-only notification actions or exact centering.
- **Settings/details:** category icons aid scanning, nested settings use a back arrow, and focused top-level screens close with ×. Activity details use a left-aligned title/date/time and top completion actions. Keep destructive actions explicitly labeled; replacing words with icons is appropriate only when the action remains familiar and accessible.
- **Persistence and memory:** the local compressed tracker snapshot remains the source of truth. Stream decode/encode rather than constructing extra uncompressed copies. Reminder/movement/weight queries skip HR arrays during parsing; workout lists and publishing select only the configured training source's samples. These are transient read-only projections: never persist them over the full snapshot. Full refresh, linking and export preserve source samples, nulls, original numbers, links and corrections. Stream bounds reject oversized inputs with actionable errors. This mitigates observed memory exhaustion; it is not evidence that every device crash is resolved.
- **Acceptance:** review at narrow widths and large text; labels and icons must not overlap. Verify calendar cancellation and move commit separately, one exercise completion with Undo, food draft close/reopen and correction without duplicates, one notification snooze path, and settings back navigation. Test large compressed histories with non-training samples: metadata projection omits arrays, training projection retains its source samples, full round-trip retains everything. Compare pre/post-upgrade records semantically because acknowledged revisions and JSON field order can change during normal synchronization. Perform synthetic writes only in isolated test storage.

Food summary polish keeps numeric consumed/target macros without three extra macro progress bars; the calorie progress track appears only when calories are known. Unknown values remain unknown. Food entry places its date and borderless close icon on a single line above the input.

### Approved visual direction — October 30

Quiet agenda is the approved visual direction: flat activity rows, prominent countdown with the complete mascot, restrained blue actions, neutral meal panels and native bottom navigation. The Focused card alternative was not selected. Earlier review-build qualifications describe the review history; visual direction is now accepted. This approval does not change the recorded testing or longer-term stability limitations.

Countdown refinement: keep the large number and quiet “days to Euros” caption on the left, with the complete mascot at the right edge. Omit the extra calendar-date line. The approved workout-row spacing stays unchanged.


### Food and Week Quiet agenda extension — October 30, 2026

Food keeps its blue consumed/target summary and single right-aligned pencil. Compact macro values put actual and target on one line; larger text stacks the three macros. Meal rows use neutral icon tiles, title and time on one line, followed by foods/portions and water. Planned meals follow totals without a Keep eating heading; entry history stays collapsible. Preserve swipe dismissal, accessible dismiss/restore actions and Undo; dismissal never records consumption. Empty meals and incomplete nutrient values remain explicit.

Week uses neutral 44 dp date tiles, with today in blue, and thin separators. Expanding a day removes its duplicated summary and displays its activities with existing log/move actions; opening another day closes the previous one. Dates remain stable across navigation; current week initially opens today. The weekly wrap-up is a full-width neutral row. Workout-row spacing and behavior are unchanged. Text can grow vertically and actions retain accessible labels and touch targets.

Training moves append an `adjust_nutrition` event to the same activity move history in the existing transactional offline write queue. Both event dates use local-time conversion and derive the current training tier from nondeleted scheduled BJJ/weights records: none/recovery, lifting only, single BJJ, or two-plus/double. Completion/skip status does not reduce this count. Apply the existing maintenance increment once. No older unmarked move is retroactively opted in; dates before the nutrition-policy boundary retain historical behavior. The backend reads the same marker and adds it for accepted schedule edits through the shared command operation. Conflicts continue through normal revision resolution; failed saves retain the move dialog, and retry never adds consumption. Budgets are derived, not separate meal-plan writes, avoiding partial budget updates during sync. Existing food records, meal IDs, portions and target snapshots are preserved; targets on move-affected dates display the derived budget. Explicit new skips still permit meal review without automatic calorie reduction. No move success dialog or request to contact an agent is shown.

Acceptance: move a lone BJJ session to an empty day and observe recovery/BJJ budgets; move into a lifting day and observe double; move back and recompute; maintenance adds its increment once; skip/logging does not change the tier; pre-policy dates stay unchanged. Check Food edit/delete and draft persistence, large text, Week expansion/navigation, and duplicate-free sync on an emulator before installing on a personal device.


October 30 home simplification supersedes the earlier split agenda: show one Today heading with all unresolved activities/steps in scheduled-time order, without Later today. After today is resolved, advance to the next unresolved dated activity under Tomorrow/weekday as before. Preserve logging, Move, Undo and each activity identity. Put the upcoming meal name and time inside its neutral card, with portions and water below; omit the separate Next meal heading. Omit the calendar-date line above the countdown; use a dominant number and quiet days-to-event caption on the left, with the complete smaller mascot aligned to the right edge. At large font scales allow vertical growth without clipping. These are presentation changes only: no record, target, reminder or sync changes.

Food does not display the generic “Training changed, ask your agent” banner, including for older schedule changes or explicit skips. Moves update the budget automatically; skipping alone still does not reduce it. Meal portions remain unchanged, and agent-based meal replanning remains available outside the app without a persistent callout.

Food simplification: remove the Keep eating heading. Planned meal rows follow the totals directly. Show the accessible restore-dismissed-meals icon only when meals have been dismissed; do not reserve an empty heading row. Preserve empty-state text, dismissal/Undo and logging.

October 30 countdown sizing revision: the countdown/mascot row and contents are 50% larger than the previous compact version. Use equal-width halves: countdown text right-aligned within the left half, complete mascot centered in the right half. Keep “days to Euros” beneath the number. Scale text, line height and mascot proportionally; allow vertical growth for larger accessibility text. Remove the redundant Euros app-header title while retaining sync/settings actions at the top right. Other screen content and activity interactions are unchanged.

Countdown spacing refinement: bring the number and days-to-event caption 10 dp closer vertically, retaining the approved sizes, equal-width columns and alignments. No behavior or persistence changes.

Home agenda heading refinement: replace the Today label with a thin neutral horizontal divider between the countdown and activities. Retain Tomorrow/weekday when displaying a future day so its timing stays explicit. Activity order, row spacing, actions and persistence are unchanged.

Home agenda divider: use a 3 dp neutral rounded rule with a short 36 dp blue leading accent. This is decorative separation, not a progress indicator; expose no progress semantics or interaction.

Activity-row optical alignment: compensate for the invisible half-height beneath the time inside its 48 dp Move target, balancing visible title/time top and bottom spacing while preserving row height and full tap targets. Derive the compensation from the current text line height; omit it when a duration line follows the time. Keep glyph and status action centered in the row.


Weekly wrap-up design refresh (October 30): lead with the title/date range and a close action. Before generation, show a concise explanation and prominent Generate wrap-up button; show loading in a soft-blue status row. Generated coaching prose leads the screen, followed by optional read-only considerations in one neutral panel. Keep summary and considerations selectable. Put the dated weight comparison and weight-entry pencil below the review, with missing-data coverage as a small final footnote. Omit redundant This week/Sunday weight headings. Refresh is an accessible icon beside Close when a result exists; errors offer Retry and preserve the valid displayed review/cache. Future weeks have no generation action. No plan-save controls or automatic plan edits.

Generation still uses the authenticated backend with the selected week's offline record snapshot and validated evidence references. The prompt now recognizes dated training-tier budgets and maintenance, and forbids prescribing new calories or treating partial food logs as complete days. It no longer claims identical daily targets. Version the review cache with this prompt change so older-policy prose is regenerated; only review metadata changes, never activities, foods, weights or outbox entries. Acceptance includes live backend generation, initial/loading/success/error/future states, cached reopen, failed-refresh preservation, accessible refresh/close/weight entry and selectable prose.


Weekly recap product treatment (October 30, supersedes the prose-first layout): a soft-blue takeaway card contains a concrete 3–7 word headline and at most two brief sentences. Follow it with zero to two flat insight rows, each with a topic icon, specific short title and concise body. Show at most one Next week consideration with a forward icon; do not invent filler to fill slots. Weight and the coverage footnote remain supporting sections. Text remains selectable; refresh, retry, empty/loading states and read-only persistence are unchanged.

The server returns schema_version 2 plus headline (≤70 characters), summary (≤280), insights (≤2; kind training/recovery/food/weight, title ≤50, body ≤240), and adjustments (≤1, ≤220). Evidence references stay internal and must match supplied records. Both backend and Android validate limits. Cache version advances to regenerate older unstructured prose. Retain summary/adjustments fields for earlier clients. Empty weeks have no provider call and empty insight/action lists. The prompt asks for natural coaching and narrower supported observations instead of stock research disclaimers such as “completion alone does not show.” It still forbids invented facts, inferred readiness, automatic plan edits, calorie recommendations or partial-log deficit claims. Coverage limitations stay in the footnote. Verify structured output, limits, topic icons, sparse content, failed-refresh preservation and an actual device generation.


### Conversational whole-week recap and weight trend — October 30, 2026

The recap reviews training, food, recovery and available server-synced Polar HR together. It has a short overview, up to four titled findings (training/recovery/food/heart rate), and up to two practical priorities, usually 250–400 words when supported. Each finding connects an observed pattern to its implication. No body-weight discussion appears in the narrative; weight stays in the chart. No automated plan changes, invented readiness claims, follow-up questions, or audit-style boilerplate. Consecutive training is evaluated in context rather than automatically condemned.

The backend supplements the submitted offline records with accepted daily nutrition targets and compact source-identified tracker summaries (duration, average/peak HR, zone time and coverage). Submitted records override matching canonical records for target derivation. Linked sessions must not be counted twice; unlinked observations cannot be blindly added to manual logs. Unknown food nutrients and missing days are not zero; recorded days are not assumed complete. Below-target patterns must be described conditionally, while today remains partial. HR comparisons require comparable sessions and adequate coverage. Only narrative generation reads these facts; no records, targets or tracker links are changed. Cache version changes require regeneration; failed refresh retains the current valid result. Server tracker updates are reflected on explicit Refresh.

The wrap-up weight chart shows all locally available observed readings through the selected week, excluding future/invalid/deleted readings. Source-linked overrides and deletion tombstones take precedence over imported points. Older cached scale readings are chart-only; they do not create historical shared records or change the maintenance policy. Weight refresh now reads 30 days separately from the shorter exercise/HR window; older cached readings remain visible. Access is limited to measurements actually shared through Health Connect, not all data held privately by another health app. Extended historical permission is not implemented.

Muted dots show individual weigh-ins. The blue trend is a trailing seven-calendar-day mean of daily means, giving each observed day one vote. Missing days are omitted, never zero-filled; at least two observed days are needed for a trend value, and gaps of seven days or more are not connected. This display calculation is separate from maintenance's seven consecutive days using the earliest reading. Latest measurement and latest available trend have text values, with dates and a measurement count; no-data state remains explicit.

Acceptance: check all observations appear without weekly-anchor filtering; verify same-day repeats do not overweight the trend, missing days stay missing, tombstones/overrides survive merging, and invalid/future points are excluded. Verify food targets and measured HR reach the model, weight values do not, richer findings pass both response validators, generation remains read-only and refresh failure preserves the displayed result.


Week date navigation (October 30): tapping the date-range heading opens the wrap-up for the currently selected week, rather than resetting to the present week. Previous/next arrows still change weeks. Close or Android Back returns to the same selected week. The date button has an accessible “Open wrap-up for [date range]” label; the Insights chart icon sits beside the dates as its visual cue, and the duplicate bottom wrap-up row is removed. Opening does not generate or write records automatically; cached, loading, empty/future and retry behavior remain unchanged. Acceptance: navigate to another week, tap its date, verify that exact week in the recap, then close and verify the selected date is preserved.


Wrap-up request stability (October 30): generation captures one immutable record snapshot per explicit tap. Background record updates and sync acknowledgements must not cancel, restart or reset that request or its loading/error state. Keep a displayed result while newer logs arrive, with a quiet refresh notice; preserve the request fingerprint in its cache. JSON key order and whitespace are canonicalized so equivalent synced payloads reuse the same cache identity. Switching weeks/closing still cancels the screen effect; no automatic paid retry is introduced. Acceptance: hold generation open, deliver a changed record, confirm the loading state remains and exactly one provider call completes; then show the result with the newer-logs notice.


Next week presentation (October 30): group suggestions in a neutral rounded card with a forward-arrow heading, small blue numbered badges and thin dividers between suggestions. Each suggestion is read-only, with no checkbox or implied plan change. New generations use at most two single-sentence actions, ideally 15–25 words and at most 180 characters each; lead with the action and avoid repeating the findings. Existing saved recaps remain readable until explicitly refreshed. Allow text to wrap at larger font scales and retain normal reading order.


Sunday Today entry (October 30): beneath the countdown, show a soft-blue Weekly wrap-up card with the Insights icon, current Monday–Sunday date range and chevron. The entire card opens that current week’s recap, regardless of the week last browsed in Week. Close/Back returns to Today. Show this card only on the device-local Sunday, independently of unresolved activities; opening does not automatically generate or write logs. Other days retain the week-date shortcut.


### Saved asynchronous weekly recaps — November 2, 2026

Generate/Refresh creates a durable request for the selected week. The app saves a UUID and immutable input snapshot before scheduling network-constrained background work. The server acknowledges quickly, persists queued/running/completed/failed status and the completed result in SQLite, and generates independently of the HTTP connection. Android polls status using short requests and caches a completed result in Room. Reopening a week displays its saved result, even when newer logs exist; a quiet refresh notice indicates changed inputs. Reopening, background sync, navigation or process recreation never automatically regenerate a recap. Refresh is explicit and preserves the displayed result if it fails. The loading panel retains its generation status without explanatory leave-screen copy.

Idempotent request IDs prevent repeat model calls when a connection is lost or Android resumes work. Background polling retries transport failures up to three worker attempts, using the same request identity. A poll attempt is bounded; provider generation is bounded at 150 seconds externally (the existing model request remains limited to 120 seconds). Persistent queued jobs resume on server startup; interrupted running jobs become a clear failed state rather than silently repeating a potentially billed call. App startup reschedules pending local jobs. Model failures have a retry action. Network-constrained jobs wait for connectivity; there is no claim of exact Android background timing. The compatibility synchronous API/MCP route remains, but native recap generation uses the job endpoints. Jobs store input/result metadata separately from canonical training/food records and never change the plan.

The approved writing prompt describes the user as a hobbyist BJJ athlete preparing for competition alongside everyday life. It discusses what happened, not perfect adherence. Targets are rough guides; numbers are approximate when useful. No grading, praise, jargon, obvious advice or repeated missing-log caveats. Unknowns remain unknown by narrowing claims or omitting unsupported observations. Body weight stays in its chart. Remove the visible coverage footnote entirely; internal coverage validation remains. Existing saved text is preserved until the user explicitly refreshes. Model remains gpt-6.1-sol; the approved assessment update below uses medium reasoning.

Acceptance: return a queued acknowledgement without waiting for inference; resubmit an identical request without another model call; persist completed results across server/client restarts; expose failed/interrupted requests rather than infinite loading; leave/reopen during generation and receive the result once; reopen a completed week without regeneration; failed refresh preserves the valid prior result; no coverage footer or domain-record writes. Keep labeled Generate/Refresh/Close controls, readable status text and natural accessibility reading order.


### Approved weekly assessment — November 2, 2026

Weekly wrap-up answers whether the observed week supports competition preparation, why, and what most deserves attention. It evaluates actual habits rather than plan compliance. Lead with a supported, week-specific assessment; connect training, HR, food and recovery observations to useful implications. Avoid unsupported readiness or progress claims, logging audits, target policing, stock coaching language and narrating internal evidence safeguards. A single lower-protein day must not dominate the overall judgment. Sparse records support narrower observations; repeated substantial low logged intake can become the main concern. Suggestions remain optional: zero actions is valid when no useful change is supported. No automatic plan changes.

The user reviewed and approved the final candidate before release. The provider remains gpt-6.1-sol; weekly assessment uses medium reasoning and prompt v11. Four final-prompt development trials completed: two real-week runs with consistent conclusions, one isolated sparse-data case, and one isolated repeated-low-food case. All passed structured-response checks and received manual content review. This small sample is not a reliability guarantee; some explanatory filler remains possible. Keep existing saved recaps until explicit Refresh, including after prompt changes. Generation, persistence, error handling and accessible native rendering are unchanged. No Android update is required for this server prompt change.

Acceptance: assessment changes appropriately with the evidence; sparse input never invents an overall verdict; repeated substantial eating patterns outrank isolated small differences; conditional scheduling suggestions do not invent fatigue or lifting effort. Verify structured output, durable async completion and identical saved-result reload. Human review of representative responses remains required before subsequent prompt rollout. Personal evaluation inputs and responses stay outside the shareable recipe and repository.

Loading-copy follow-up: remove “You can leave this screen.” Keep the existing generation status and background-job behavior; no replacement explanatory sentence.


### Food logging check-in suppression — November 2, 2026

The 7:30 PM food logging check-in is eligible only when that local date has no saved, nondeleted food entries. One entry is enough, including an unsynced offline entry or an entry with incomplete nutrients; do not judge whether a full day or meal was logged. Use the latest revision per food identity. Other dates, drafts, planned meals and deleted entries do not suppress it. Deleting or moving the last entry restores eligibility only under existing timing rules; never replay an expired reminder. Reconciliation removes pending/snoozed/delivered check-ins once food is saved, and delivery rechecks current records. Planned meal/water reminders and other reminder types retain their separate eligibility. No new controls or copy. Acceptance: saved same-day entry suppresses the check-in; another date does not; latest deletion/date correction wins; meal-time reminders remain independently available.


### Launcher destination repair — November 4, 2026

Opening the launcher icon routes to Today and clears detail/weight/settings/review overlays. A newly tapped reminder still opens its requested destination. Consume reminder intent extras and deep-link data once so recreation cannot replay an old weigh-in notification. Configuration recreation retains saved navigation instead of replaying the launch request. Use a single-task main Activity so launcher/reminder intents reach the existing instance. This changes navigation only: no data, drafts, targets or reminder eligibility are modified. Acceptance: weigh-in reminder opens Weight; launcher reopen goes to Today; a fresh weigh-in reminder still opens Weight; consumed intent cannot reopen Weight on recreation. Keep normal in-app back/close navigation and accessibility labels.


### Keeping planning documents current

Maintain one active backlog that separates shipped features, remaining engineering work, optional integrations and personal schedule decisions. Archive superseded proposals with clear provenance instead of carrying their dates, session counts or estimates forward as requirements. Record the recipient's preferred weekly pattern separately from an applied dated schedule; inspect their current records before drafting an extension, preserve confirmed times and historical logs, and review holiday/travel/taper exceptions before applying a revision. Documentation cleanup alone never changes scheduled activities or creates reminders.


When applying an accepted recurring extension, use explicit start/end dates, local timezone and confirmed times. Stop regular sessions at the accepted taper boundary; travel dates without times do not authorize invented appointments. Preview and validate all revision-checked commands, preserve existing histories and tombstones, and read back every affected record through the real API. Update future target/schedule provenance consistently while keeping accepted meal portions unchanged; track meal-time mismatches as a separate planning task. Confirm the phone receives the same dated revision. Preserve a private before/after command audit; exclude personal snapshots from this reusable recipe.


An accepted taper can use reduced familiar training plus flexible rest/movement activities. Optional alternatives belong in instructions rather than duplicate required workouts. Record planned local timezone explicitly, preserve missing flight/competition times, and stop before an unassigned event. Existing activity display and day grouping remain tied to the configured home timezone; storing a travel-zone offset does not by itself implement travel-aware UI, quiet hours or date grouping. Track and communicate that limitation before travel.


### Device-local travel display — November 4, 2026

Activity times, Today/Week grouping, tracker presentation, Move, current date, snooze, quiet hours and daily reminder wall clocks follow the device timezone. Stored offset instants do not move when the phone travels: a Portugal 10:00 slot displays 04:00 in Chicago and 10:00 in Lisbon. New food drafts/manual observations use the current zone; edits retain existing record dates/zones. Explicit moves record the new device zone while preserving move history. On timezone broadcast or a changed zone at foreground entry, recreate the Activity through normal saved-state restoration and reconcile reminders; settings need no new timezone control. Meal times are local wall-clock slots, while dated activities retain absolute instants.

Preserve backend contracts: dated nutrition/move-budget calculations, weekly assessment input membership and imported scale-day attribution remain aligned with the existing Chicago canonical policy. Travel display is not a migration of historical local_date fields or backend reporting timezone. Existing source weights must not be rewritten merely because the phone travels. A late-night cross-zone activity can therefore appear on a different display date than its canonical reporting date; full travel-local backend reporting is separate work.

Acceptance: test the same Lisbon slot in Chicago and Lisbon; test local-midnight display grouping and local Move offsets; verify daily weigh-in/meal clocks and quiet hours in Lisbon; retain old food dates and imported scale attribution; keep nutrition calculations consistent across zones. Exercise native row/Move UI on an emulator set to each actual zone, restore its original zone, and preserve personal phone data during installation. No simulated timezone changes on the user's phone are required.

## November 4: linked workouts and nutrition sharing

Automatically associate a saved planned BJJ or weights session with an imported preferred-source workout only when both have exactly one compatible candidate within 90 minutes of the planned start instant. Martial arts matches BJJ; strength training/weightlifting matches weights. Ignore deleted observations, skipped/deleted plans, already linked workouts and explicitly excluded pairs. Ambiguous or other-sport matches retain Choose workout. Existing manual links win; Unlink persists an exclusion so refresh cannot undo it. Matching runs after imports and activity changes, persists in the existing tracker snapshot and publishes through the existing source-identified tracker channel. It never changes completion, scheduled time, calories or measured values.

Week renders a linked workout once, on its planned row, with an accessible heart-rate icon and recorded duration; the detail includes the measured recap and Choose/Unlink controls for BJJ and weights. Unmatched observations remain separate. If the planned record is deleted, the observation becomes visible separately again. Unknown or deleted HR stays absent; no invented curves or completion. Matching and display compare instants, so travel display does not change the association.

Settings → Health Connect → Food logs offers Share food logs, requiring the platform nutrition-write grant. It includes existing saved food entries and subsequent local or server-synced changes. Export only saved consumption: name, consumed instant/original offset and known kcal/protein/carbohydrate/fat; unknown nutrients remain absent and explicit zero stays zero. Plans, targets, drafts and inferred intake are never exported. The platform's interval record uses a one-millisecond technical interval at the consumed timestamp; this is not a measured eating duration.

Each food identity maps to one stable platform client ID. Persist a monotonically increasing version and content fingerprint before writing, acknowledge only after success, and retry the same version after interruption. Edits replace that record; tombstones delete only the app-owned matching ID. Room food records/tombstones are the durable work source. Enqueue on food changes, foreground entry and a separate periodic worker; background-read permission is not needed for this write path. Platform scheduling is approximate. Revocation retains pending work and displays Nutrition write access needed; transient failure retains work with a retry action. Food logging itself remains available offline and without the grant. Stop sharing prevents further processing and leaves previously shared entries in Health Connect. A call already in flight may finish. Device-local sharing preference/checkpoints and OS grants are not portable backup settings; a replacement device requires explicit opt-in. This is one-way publishing, not importing or merging another app's food logs; other apps' consumption of shared records is outside Euros' control.

Acceptance: one-to-one linking across timezone displays, ambiguous matches in both directions, explicit unlink/skip/deletion, preserved source values and completion; one Week row and reachable recap; stable food identity across retry/edit, increasing version despite local clock rollback, missing versus zero nutrients, original consumed time/offset, permission denial/revocation, stop-sharing, and real platform edit/delete readback. Synthetic platform tests run only on the emulator and clean up their own records.


### Tracker history storage and platform timestamp precision

Keep the compressed imported-history snapshot in atomic metadata chunks, each at most 500,000 characters, so growing history does not exceed the database cursor's single-row limit. Read legacy single-row snapshots and transparently assemble chunked snapshots for existing readers, observers, exports and restore. Replace the manifest and all chunks in one transaction; a rejected write retains the previous snapshot. Removing local tracker data removes its chunks too. Keep all source identities, samples, corrections, tombstones, explicit links and unlink exclusions. Splitting storage does not authorize trimming history or altering shared activity/food records. Bound both decoded bytes (50 MB) and encoded storage (16 MB); reject oversize writes before committing an unreadable state. This is bounded local storage, not unlimited historical access.

Nutrition export uses Health Connect's millisecond timestamp precision; preserve the original higher-precision consumed timestamp in Euros. The technical one-millisecond platform interval begins at that truncated instant. Compare provider readback at its supported precision, while verifying original offset, known nutrients, unknown values, unique identities and deletions.

Acceptance: large snapshots round-trip through storage, observation and portable export without oversized rows; legacy reads work; replacing large with small removes obsolete chunks; rejected replacement preserves old state; missing chunks fail explicitly; real phone refresh succeeds without losing previously cached source identities or links. Source snapshots and provider records used for release verification remain private; count-only results may be documented.

### Strength session feedback — November 4, 2026

Weights Done (row checkmark or detail) saves completion with default “About right” and opens “How did it feel?” with Easy / About right / Too hard. No Workout done confirmation, secondary exercise question, Not now, or set-by-set entry. Toolbar Back and Android Back retain the default and return to the originating main tab. A selected response is saved to that same activity before closing; errors retain the screen, and controls are disabled during the save. Default versus explicitly selected feedback is distinguished internally; neither is an observed rep count, load, duration or numerical effort rating. Existing completed sessions without feedback remain unknown until explicitly edited; imports do not fabricate feedback.

Completion and the default are one local transaction with a durable offline outbox entry. Selecting a response appends a revisioned update; an identical retry is a no-op. Preserve the original completion timestamp and merge into the latest payload so concurrent schedule moves survive. Reject feedback after skip/deletion or a different completion. Skip and completion Undo remove feedback; Undo retains existing revision/conflict protection. Rotation restores the feedback destination. An interruption after Done retains completion and its default. Existing prescribed exercise snapshots and loads remain intact. The fixed-load progression policy below uses reps and hold times; the earlier mockup’s load changes are not used.

Acceptance: both Done entry points; all three choices; toolbar/system Back; recreation; offline write/retry/reload; Undo; skip/deletion while open; concurrent move; large text and reachable labeled controls. Verify payload round-trip through the existing shared server store without invented measurements or changes to schedules/targets.

### Fixed-load progression from strength feedback — November 4, 2026

A newly completed prescribed weights routine supplies the next matching pending routine. Easy adds one to numeric reps per set and five seconds to numeric holds; Too hard subtracts the same; About right carries the latest prescription forward. Preserve dumbbell loads, set counts, exercise identities, order, and free-text/range prescriptions. Numeric bounds are 1–30 reps and 5–120 seconds; values outside those supported ranges are unchanged. Do not infer actual weights lifted or reps achieved. These are deterministic adjustments to the prescription from whole-session feedback, not measured performance.

Match the exact authored exercise list (including sets, reps, loads and holds) and phase, independently of JSON object key order. Upper/lower routines do not cross-match. Only nondeleted completed sessions with feedback, a recorded completion before the target, and an earlier scheduled instant can supply an adjustment. Pick the latest actual completion, breaking a tie by stable identity. An authored prescription change starts a new matching group. External-app instructions without exercises retain feedback but have no invented routine or progression.

Store the effective template, policy version, source activity identity, source feeling and concise change summary in `strength_progression` beside the original authored prescription/snapshot. Show that effective template and its summary in pending activity details. Once completed, its prescribed template is frozen for subsequent progression; it is not claimed as an observed performance log. Recalculate pending prescriptions on local completion/feedback, correction, skip, Undo, deletion, move and after incoming sync. An unchanged calculation adds no command. Undo/deletion of a source recomputes pending descendants; already completed prescription snapshots stay intact.

Local feedback and affected pending prescriptions commit in one Room transaction and append independent immutable commands to the existing outbox. Server acceptance is revisioned per record, not a cross-record batch: retries converge and conflicts stay visible through existing sync controls. A conflicted target is left untouched. API/MCP readers receive the saved effective template as activity metadata; no new inference provider or MCP service is required. Recalculation of remote edits happens when Android syncs; the server does not independently run the progression engine. Keep this boundary explicit.

Acceptance: easy/hard/steady, fixed loads/sets, holds, unsupported text/ranges, numeric limits, separate routines, absent/skipped/deleted/future source, idempotent recalculation, next completion carries the adjusted prescription, correction/Undo restores pending plans, completed snapshots remain unchanged, and feedback plus adjustments survive the offline outbox. Existing later external-app placeholders require an explicit routine-planning correction before they can progress.

### Persistent comma-separated food estimates — November 8, 2026

Food Add splits the input on commas, trims each item and ignores empty segments. Exact remaining text is the key: quantity, case and wording variations are separate entries. No fuzzy matching, quantity extrapolation or food-name deduplication. Each comma is a food separator, not a supported thousands separator or cross-item correction clause. Up to 30 items and the existing 8,000-character request limit apply. A legacy explicit total-weight Details suffix keeps the entire request together, including its weight, to avoid misapplying a meal-wide amount to one item.

Look up each item in a persistent backend food-item table before inference. Only uncached items invoke the provider, with at most two item calls in flight and existing shared model quotas still enforced per actual call. A bounded per-key lock coalesces successful identical misses in the single server process. Save validated complete calories/macros with original quantity, model and prompt provenance; unknown total mass is allowed. Do not cache failures, clarification requests or incomplete nutrients. Cache entries survive restart and normal model/prompt changes; they have no automatic expiry. User edits to a consumed food entry do not rewrite the estimate cache. No cache-management UI is introduced.

Sum item nutrients with decimal arithmetic. Repeated occurrences contribute repeatedly but reuse one cached calculation. Total mass is unknown if any item mass is unknown. Retain the original full input on the review draft and component provenance for combined estimates. Wait for all item calls: successful siblings persist even when one fails. Failed requests return an error; unresolved multi-item requests return clarification with unknown totals, never a seemingly complete partial meal. Retrying reuses successful items and only retries unresolved ones.

App API and MCP use the same cache service. Existing native review/edit/final save and accessible/error states remain; no new screen or heading. Cache writes are calculation metadata, not food consumption or plan writes. Every final save remains a separate dated food log through the normal offline queue. A backend connection is still required for Add; caching does not add offline interpretation. SQLite server backups include the table; phone portable exports contain saved food snapshots, not the server estimate cache.

Acceptance: exact repeat and mixed hits/misses; restart; duplicate occurrences; independent quantity keys; concurrent identical requests; precise totals and unknown mass; failed/uncertain item retry retaining siblings; empty/oversized inputs; whole-entry weight context; API/MCP shared reuse; cache hits consume no inference allowance and distinct misses retain rate limits; no food records or change-feed writes before final save. Deploy verification must exercise the actual authenticated public API and MCP, comparing inference counts for cold versus repeated inputs.


Scheduled training remains the budget source even after an explicit skip; neither meal drafting nor move-derived budgets drop the skipped session. Confirmed rest days never inherit the provisional recurrence. Competition-day food may retain an explicitly provisional training tier while event timing remains unknown, without inventing a dated workout or a timed pre-training meal.

Acceptance: from a Week workout row, open Move; changing the draft time then Cancel activity retains the original schedule and saves skipped. Closing with ×, outside tap or Back preserves the record and outbox. A successful cancellation synchronizes like detail Skip and removes the workout from unresolved tasks.
