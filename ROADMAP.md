# Roadmap

The core personal app is implemented. This list distinguishes remaining options from ongoing observation; it is not a commitment to add every possible feature.

## Remaining options

- Older Health Connect history where the platform and source app make it available, preserving source identity and corrections.
- Optional external meal-planning and shopping nudges, after choosing a delivery channel and cadence. Local meal/prep notifications already exist.
- CSV export, deferred until there is a concrete use case. JSON export and additive restore already work.

Competition/travel scheduling remains personal planning work as event and itinerary details become available, rather than a new app feature.

## Ongoing observation

- The current weekly assessment is useful enough to keep. No prompt redesign or broad evaluation project is queued; revisit specific unhelpful results when they arise and review material changes before release.
- The accessibility/reliability pass and isolated restore rehearsals are complete. Continue observing real use for concrete defects. Bounded tests and basic TalkBack focus checks do not establish multi-day stability or a full spoken audit.

The proposed completed-session choice dialog and Schedule another flow were dropped before release. Existing Move behavior stays.

## Possible reuse work, outside the current personal-app backlog

Centralized personalization, a dedicated Android demo build, broader model-fixture evaluation, portable backup automation and alternative authentication are future possibilities, not active commitments. The existing [demo seed](docs/DEMO.md) already creates fictional data in an explicitly selected new database. There is no separate Android demo mode.

## Completed recently

- Saved asynchronous weekly assessments, selected-week navigation and failure recovery.
- Automatic unambiguous planned/tracker workout links and opt-in nutrition sharing through Health Connect.
- Growing tracker-history storage, complete observed-weight charts and a seven-day trend.
- Device-local travel display, reliable launcher reopening and food-nudge suppression after logging.
- Large-text layouts, accessible activity/date labels, tracker-detail error recovery, and repeated launcher checks.
- File-backed additive restore with restart, existing tombstones/pending writes and duplicate-merge checks.

The reference deployment passed an isolated server snapshot/restore/restart rehearsal. Its host-specific scripts and private snapshots are excluded here; follow the recipe's deployment-neutral recovery procedure for your own setup. Scheduled backup coverage and whole-host recovery remain separate operational checks.
