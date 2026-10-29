# Demo data

All seed values are fictional and separate from the bundled planned activities. The seed creates 19 records: seven dated activities (done, skipped and pending), seven meal plans, three food entries and two weights. Planned meals never create food intake. No real tracker samples, credentials or model calls are included. Daily meal targets are illustrative, not a prescription.

Install the backend first, then explicitly select a new file and the date you want to explore:

```sh
server/.venv/bin/python scripts/seed_demo.py --db /tmp/euros-demo.sqlite3 --start 2026-06-08
export EUROS_DB_PATH=/tmp/euros-demo.sqlite3
# Set separate demo API/MCP credentials as described in server/README.md.
server/.venv/bin/euros-server --port 3013
```

For a current-week demo, replace the start date yourself. Use an emulator or separate test installation; do not connect a personal phone with queued records to a demo server. The Android bundled schedule is separate and initializes its own pending activities. Use the matching demo dates in Week/Food.

The seeder refuses every existing file, including an empty database. Reset by stopping the demo server and choosing a NEW database filename, then pointing EUROS_DB_PATH to it. Keep old files until no longer needed. Do not delete or replace your personal database as part of testing.

`contracts/` holds synthetic API fixtures. `scripts/fixtures/schedule-plan.json` freezes an earlier example schedule for generator regression tests; the current bundled plan is checked separately. Automated tests use temporary databases and mocked model providers.
