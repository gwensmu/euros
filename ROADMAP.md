# Roadmap

Euros is a working personal app. These are remaining improvements, not promises of shipped behavior.

- [ ] Centralize competition dates, timezone, nutrition rules, HR zones and schedule defaults so personalization does not require searching code.
- [ ] Add a dedicated Android demo build with fictional records and no personal-server enrollment.
- [ ] Expand device tests for Health Connect permission revocation, background refresh, notification delivery and offline recovery.
- [ ] Add an automated backend backup/restore rehearsal and document deployment-neutral recovery.
- [ ] Add finer-grained agent permissions and evaluate OAuth for MCP clients.
- [ ] Evaluate food interpretation with a broader labeled fixture set and provider-model compatibility checks.
- [ ] Reassess the value and tone of weekly reflections using explicit user feedback before expanding them.
- [x] Check narrow layouts at 200% text, expose activity/date accessibility labels, and verify basic TalkBack focus. A full spoken audit and longer device soak remain follow-up checks.

The existing [demo seed](docs/DEMO.md) creates fictional data in an explicitly selected new database. It is not yet a separate Android demo mode.

## Recent improvements

- Saved asynchronous weekly assessments, selected-week navigation and failure recovery.
- Automatic unambiguous planned/tracker workout links and opt-in nutrition sharing through Health Connect.
- Growing tracker-history storage, complete observed-weight charts and a seven-day trend.
- Device-local travel display, reliable launcher reopening and food-nudge suppression after logging.
- File-backed additive restore rehearsal with restart, existing tombstones/pending writes and duplicate-merge checks.

The separate reference deployment passed an isolated server snapshot/restore/restart rehearsal. Its host-specific scripts and private snapshots are not included here; use the recipe's deployment-neutral recovery procedure for your own setup.
