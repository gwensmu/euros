# Euros backend

Python 3.12+, Starlette/Uvicorn, SQLAlchemy, PostgreSQL and FastMCP (SQLite for local development). The app API, account site and MCP share domain operations. Weekly AI wrap-up has been removed.

```sh
python3 -m venv server/.venv
server/.venv/bin/pip install -r server/requirements-dev.lock
server/.venv/bin/pip install -e server --no-deps
export EUROS_DATABASE_URL_FILE=/absolute/path/to/private/postgres-url
server/.venv/bin/euros-server --create-account athlete
server/.venv/bin/euros-server --port 3013
```

Account creation prompts for a password. One persistent PostgreSQL database contains identities, browser sessions, OAuth grants and account-owned training data. SQLAlchemy composite primary keys, user foreign keys and account-scoped sessions protect domain access. Authentication uses SQLAlchemy Core transactions in the same database. See [PostgreSQL operations](POSTGRES.md) for the container, private connection secret, migration and backup setup. Set `EUROS_DATA_DIR` instead only for a local shared SQLite database. Clients cannot choose a row's owner. Sync cursors and command retries are per account.

The server binds to loopback. Supply your own HTTPS proxy/tunnel, `--public-host training.example.com` and `--public-url https://training.example.com` for external clients. These flags do not provision hosting. API routes are under `/v1/`, MCP at `/mcp`, and the account site at `/account/`. Sign-up is closed unless `--allow-signup` is explicitly enabled. The Android app and MCP clients use OAuth authorization code with PKCE and read/write scopes; the account website handles passwords.

Optional static credentials use `EUROS_STATIC_ACCOUNT` to name an existing account plus separate `EUROS_API_TOKEN_FILE` and `EUROS_MCP_TOKEN_FILE`. Token files must contain independent random ASCII values of at least 32 characters and stay outside the repository. Environment alternatives are `EUROS_API_TOKEN` and `EUROS_MCP_TOKEN`. Static credentials remain restricted to their API or MCP surface. No credentials or original hosting endpoint are supplied.

Optional model calls use `OPENAI_API_KEY_FILE` (or `OPENAI_API_KEY`) and `EUROS_FOOD_MODEL`. Select a model available to your provider account. Food results are editable drafts; reminder output selects an approved joke. Credentials remain server-side. Persistent per-account allowances do not replace provider spending controls.

## Migration and recovery

Stop the old service before migration. For an earlier per-user SQLite layout, run `--consolidate-databases`: it snapshots `accounts.db` and `users/<id>/euros.db` into one shared `euros.db`, preserves account credentials, IDs, revisions, tombstones, cursors and command receipts, and installs the file only after validation. Source files remain unchanged. Unknown tables/columns or orphan account directories stop migration rather than dropping data.

For a personal database, create a standalone SQLite backup snapshot from a preserved copy with the old writer stopped. Then first create the destination account, then run `--adopt-database /private/old.sqlite3 --username athlete`. This refuses a populated destination account and preserves the source. Do not expose the new service until migration is checked. Android caches are bound to server and account: a populated legacy cache cannot silently become a different account's cache. An explicit phone-cache migration remains separate work; keep existing enrollment and unsynced data until then.

The Python `create_app(Store(path), api_token, mcp_token)` factory remains available for existing personal deployments and uses the same shared schema with a reserved legacy owner. Normal CLI startup uses account mode. Schema version 2 cannot be opened by older server releases. Keep a consistent pre-upgrade backup and matching old image for rollback; never overwrite newer writes with an older backup.

Back up the entire PostgreSQL database with `pg_dump` and verify recovery with `pg_restore` into a separate database. In local SQLite mode use SQLite's online backup API to include WAL contents. Keep backups private and test restoration separately. `--list-accounts`, `--set-password USERNAME` and `--import-plan FILE --username USERNAME` support local administration. Account deletion/export and automatic phone account switching are not implemented. Live external OAuth/client compatibility still requires deployment-specific verification.

See the [API contract](../docs/API_CONTRACT.md) and [recreation recipe](../docs/shareable/PERSONAL_TRAINING_APP_BLUEPRINT.md). No demo records are installed automatically. The explicit demo seeder creates an isolated personal database for development only.
