# Shared PostgreSQL operations

Use PostgreSQL 18 in a dedicated service, with a named volume mounted at `/var/lib/postgresql`. Keep the database on an internal application network without a published port. Use an independent non-superuser application role and an application-owned database. Store the administrator password separately from the application's connection secret. Keep the volume across releases; never use `docker compose down -v` against real data.

Set `EUROS_DATABASE_URL_FILE` to a private mounted file containing the application's `postgresql+psycopg://ROLE:URL_ENCODED_PASSWORD@DATABASE_HOST/DATABASE` URL. `EUROS_DATABASE_URL` is an alternative; the file takes precedence. Never print or commit the connection string. PostgreSQL configuration takes precedence over the local `EUROS_DATA_DIR` SQLite fallback. Run the normal `euros-server --create-account athlete@example.com` command before first sign-in. A username or email is accepted; email ownership/recovery is not implemented. Sign-up remains closed unless explicitly enabled.

The application initializes its SQLAlchemy metadata on startup. This is initial schema creation, not a versioned DDL migration framework; future schema changes need explicit reviewed migration steps. Application sessions enforce ownership; database row-level security is not configured. The database credential is an operator credential with access to all application tables and must remain server-side.

## Import an existing personal SQLite database

1. Stop the old application and all other writers. Preserve the original SQLite files and any WAL/SHM files. Copy them to a private backup directory. Use SQLite's `Connection.backup` API against the copy to produce a standalone snapshot; never assume copying only the main file of a live database is complete.
2. Create the destination account in PostgreSQL. Do not launch handlers that seed account catalog data before importing. The destination account must have no training data or previous import ledger. For an already-enrolled personal phone, preserve its established legacy account ID through an explicit operator migration; do not assign that identity to an unrelated user or clear the phone's cache.
3. With the new application still stopped, run `euros-server --adopt-database /private/standalone.sqlite3 --username athlete@example.com`. The configured PostgreSQL database is the destination. The importer opens the source immutable/read-only, rejects a nonempty sibling WAL, verifies the source schema, copies and hashes every source row, then commits atomically. A failure rolls back. Original records, revisions, IDs, tombstones, retry results, cursors, caches and history are retained; retired review jobs are archived. Source files are never deleted.
4. Compare API records by their stable identity, not list position: database collation may change list order. Check dated nutrition responses and change-feed continuity. Start the application only after parity succeeds.
5. Verify the actual public HTTPS routes: `/v1`, `/mcp`, `/account`, `/oauth`, `/.well-known`. A tunnel restricted to the first two paths will break browser sign-in. Exercise login, consent/PKCE, read-only scope refusal, refresh, revocation, API/MCP, phone sync and food lookup. Preserve device enrollment, drafts and pending commands.

This importer accepts the legacy single-account schema only. The separate `--consolidate-databases` command combines old per-user SQLite directories into shared SQLite v2; it does not migrate SQLite v2 into PostgreSQL. Unknown schemas fail instead of silently losing tables.

## Backup and recovery

Use `pg_dump -Fc` for a consistent backup of the whole database. Protect dumps as sensitive user data. Restore into a separate database using `pg_restore --exit-on-error`; verify tables/records before considering the backup usable. Keep account/session/OAuth tables with domain data. Retain original SQLite, previous application image and device backup until actual client verification succeeds.

After PostgreSQL accepts new writes, reverting to the original SQLite snapshot loses them. Preserve the current PostgreSQL database before recovery and reconcile those writes explicitly. Automatic backup scheduling and retention are not provided by this change.

## Tests

Set `EUROS_TEST_DATABASE_URL` to a disposable PostgreSQL server and run `python -m pytest -q server/tests`. Tests create random schemas and drop only those schemas. Never use production credentials for tests. CI runs PostgreSQL integration tests covering account isolation, concurrent commands, atomic rollback, quotas, legacy import and source preservation, alongside the SQLite suite.
