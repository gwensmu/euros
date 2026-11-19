# Shared PostgreSQL operations

Use PostgreSQL 18 in a dedicated service, with a named volume mounted at `/var/lib/postgresql`. Keep the database on an internal application network without a published port. Use an independent non-superuser application role and an application-owned database. Store the administrator password separately from the application's connection secret. Keep the volume across releases; never use `docker compose down -v` against real data.

Set `EUROS_DATABASE_URL_FILE` to a private mounted file containing the application's `postgresql+psycopg://ROLE:URL_ENCODED_PASSWORD@DATABASE_HOST/DATABASE` URL. `EUROS_DATABASE_URL` is an alternative; the file takes precedence. Never print or commit the connection string. PostgreSQL configuration takes precedence over the local `EUROS_DATA_DIR` SQLite fallback. Run the normal `euros-server --create-account athlete@example.com` command before first sign-in. A username or email is accepted; email ownership/recovery is not implemented. Sign-up remains closed unless explicitly enabled.

Schema changes are versioned Alembic migrations (see [Schema migrations](#schema-migrations)). Application sessions enforce ownership; database row-level security is not configured. The database credential is an operator credential with access to all application tables and must remain server-side.

## Import an existing personal SQLite database

1. Stop the old application and all other writers. Preserve the original SQLite files and any WAL/SHM files. Copy them to a private backup directory. Use SQLite's `Connection.backup` API against the copy to produce a standalone snapshot; never assume copying only the main file of a live database is complete.
2. Create the destination account in PostgreSQL. Do not launch handlers that seed account catalog data before importing. The destination account must have no training data or previous import ledger. For an already-enrolled personal phone, preserve its established legacy account ID through an explicit operator migration; do not assign that identity to an unrelated user or clear the phone's cache.
3. With the new application still stopped, run `euros-server --adopt-database /private/standalone.sqlite3 --username athlete@example.com`. The configured PostgreSQL database is the destination. The importer opens the source immutable/read-only, rejects a nonempty sibling WAL, verifies the source schema, copies and hashes every source row, then commits atomically. A failure rolls back. Original records, revisions, IDs, tombstones, retry results, cursors, caches and history are retained; retired review jobs are archived. Source files are never deleted.
4. Compare API records by their stable identity, not list position: database collation may change list order. Check dated nutrition responses and change-feed continuity. Start the application only after parity succeeds.
5. Verify the actual public HTTPS routes: `/v1`, `/mcp`, `/account`, `/oauth`, `/.well-known`. A tunnel restricted to the first two paths will break browser sign-in. Exercise login, consent/PKCE, read-only scope refusal, refresh, revocation, API/MCP, phone sync and food lookup. Preserve device enrollment, drafts and pending commands.

This importer accepts the legacy single-account schema only. The separate `--consolidate-databases` command combines old per-user SQLite directories into shared SQLite v2; it does not migrate SQLite v2 into PostgreSQL. Unknown schemas fail instead of silently losing tables.

## Schema migrations

The PostgreSQL schema is defined by reviewed Alembic revisions in `server/src/euros_server/migrations/versions/`; `0001` is the baseline, the schema as it stood when versioning began. SQLite (local tests, legacy imports) keeps its own built-in upgrade and is not migrated this way.

At startup the service checks the database against the newest revision it ships:

| Database | Startup |
| --- | --- |
| Empty | Creates the schema at the newest revision (nothing to lose) |
| At the newest revision | Starts |
| Behind (pending revisions) | Refuses: back up, then run `--migrate` |
| Tables but no version record (created before migrations) | Refuses: back up, then run `--migrate` |
| At a revision this release does not know | Refuses, unless `EUROS_ALLOW_NEWER_SCHEMA=1` |

`euros-server --migration-status` prints the state, current and newest revisions and pending revisions without changing anything. `euros-server --migrate` applies pending revisions in one transaction under an advisory lock, so a failure changes nothing and two operators cannot migrate at once. Both use the configured database URL and need no listener.

**Deploying a release with a migration:** take and verify a backup ([below](#backup-and-recovery)), stop the app container, run `--migration-status` then `--migrate` from the new image against the production database (`docker compose -f compose.production.yaml run --rm --no-deps --entrypoint euros-server app --migrate`; the database service keeps running), then start the new release. A release without a migration needs none of this.

**First adoption of the existing database:** the production database predates versioning, so it has the baseline's tables and no version record. `--migrate` adopts it at `0001` only if its schema is exactly the baseline's: it builds the baseline in a temporary schema inside a transaction that is always rolled back, compares tables, columns, types, keys and indexes, and refuses with the list of differences otherwise. Nothing is changed by a refused adoption. Run `--migration-status` first; expect `unversioned`.

**Rolling back code:** the older image refuses a database at a revision it does not know. When the newer revision only added things (tables, nullable columns, indexes), set `EUROS_ALLOW_NEWER_SCHEMA=1` for the older image. Otherwise restore the pre-migration backup; the baseline cannot be downgraded, and no migration is downgraded automatically.

**Authoring a revision:** change the models, then against a disposable database at the newest revision run `EUROS_DATABASE_URL=postgresql://... alembic revision --autogenerate -m "Describe the change"` from `server/`. Review and edit the generated file: autogenerate misses renames (it emits a drop and an add, losing data), data backfills and some constraint changes. Prefer additive steps that the previous release can run against. `test_migrations_build_exactly_the_models` fails CI when the models and the migrations disagree.

## Backup and recovery

`infrastructure/isolation/backup-postgres.py` backs up the production database on the deployment host, using the host's Python and Docker. Run it from the deployment root, set `EUROS_DEPLOYMENT_ROOT`, or pass explicit `--compose` and `--output` paths:

1. `pg_dump -Fc` of the application database from inside the database container (one consistent snapshot while the service runs), and `pg_dumpall --globals-only --no-role-passwords` for roles.
2. Restores the dump into a disposable container from the same pinned PostgreSQL image, with no network, using `pg_restore --exit-on-error`; counts every table's rows and reads the schema revision there. A dump that does not restore, or restores at a different revision, fails the run.
3. Only then writes the backup's files and a manifest: checksum, size, schema revision and restored row counts. A backup without a manifest is not a verified backup.
4. Keeps the newest backup of each of the last 14 days, 8 ISO weeks and 12 months (`--keep-daily/--keep-weekly/--keep-monthly`) and deletes older verified backups. A failed run deletes nothing.

Files are owner-only in an owner-only `backups/postgres` directory under the deployment root; they contain every account's data and password verifiers. `--check-recent 26` exits non-zero unless a verified backup is under 26 hours old, for any monitor. Run it daily with the launchd template `com.euros.postgres-backup.plist.example` (03:30; a missed run happens at next wake). The template is not installed by any tool. Backups stay on the deployment host; an off-host copy is not set up, so losing that disk loses the database and its backups together.

**Restoring.** Never restore over the live database. With the app container stopped:

1. Pick a backup and check its file against the manifest: `shasum -a 256 euros-STAMP.dump` matches `sha256`.
2. Create a new empty database owned by the application role, as the administrator inside the database container: `createdb -U postgres -O ROLE euros_restored`. If the role itself is missing (new cluster), apply `euros-STAMP.globals.sql` with `psql -U postgres`, then set the role's password from the existing connection secret with `ALTER ROLE`; role passwords are not in backups.
3. `pg_restore -U postgres --exit-on-error --no-owner --role=ROLE -d euros_restored < euros-STAMP.dump` (pipe the file in with `docker compose exec -T database`).
4. Compare table row counts with the manifest's `restored_row_counts`, and `--migration-status` with its `schema_revision`.
5. Point the connection secret at `euros_restored`, start the app, and verify API, MCP, sign-in and phone sync. Keep the previous database until that succeeds; writes made after the backup are not in it.

Retain original SQLite, the previous application image and device backups until actual client verification succeeds. After PostgreSQL accepts new writes, reverting to the original SQLite snapshot loses them.

## Tests

Set `EUROS_TEST_DATABASE_URL` to a disposable PostgreSQL server and run `python -m pytest -q server/tests`. Tests create random schemas and drop only those schemas. Never use production credentials for tests. CI runs PostgreSQL integration tests covering account isolation, concurrent commands, atomic rollback, quotas, legacy import and source preservation, alongside the SQLite suite.
