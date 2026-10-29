# Euros backend

Python 3.12+, Starlette/Uvicorn, SQLite and FastMCP. Run from the repository root:

```sh
python3 -m venv server/.venv
server/.venv/bin/pip install -r server/requirements-dev.lock
server/.venv/bin/pip install -e server --no-deps
```

Set `EUROS_DB_PATH` to a persistent writable database path. Set `EUROS_API_TOKEN_FILE` and `EUROS_MCP_TOKEN_FILE` to separate private files containing independently generated random ASCII tokens of at least 32 characters. Keep them outside the repository with restrictive permissions. Environment alternatives are `EUROS_API_TOKEN` and `EUROS_MCP_TOKEN`.

```sh
server/.venv/bin/euros-server --port 3013
```

The server binds to loopback. The app API is `/v1/`; Streamable HTTP MCP is `/mcp`. To host it on an owner-operated computer, use your own HTTPS reverse proxy/tunnel and process supervisor. Add the intended hostname with `--public-host`. This flag configures allowed hosts/origins; it does not provision a tunnel. Keep the database outside release directories.

Optional model calls use `OPENAI_API_KEY_FILE` (or `OPENAI_API_KEY`), `EUROS_FOOD_MODEL`, and `EUROS_REVIEW_MODEL`. Select model IDs available to your account. Model calls produce drafts/read-only output, never automatic plan edits. Credentials stay server-side. Bounded requests and persistent inference allowances reduce exposure but do not replace provider spending controls.

App and MCP bearer credentials are separate coarse access boundaries, not per-tool OAuth scopes. Do not expose an unauthenticated origin. Rotate tokens by replacing their private values and restarting. No owner credentials or deployment endpoint is provided.

Back up a live database with SQLite's backup API so WAL contents are included. Store backups privately, test restoration on a separate database, and preserve unsynced phone writes before recovery. Android replacement installs should preserve data; do not uninstall to update.

See [API contract](../docs/API_CONTRACT.md) and [recreation recipe](../docs/shareable/PERSONAL_TRAINING_APP_BLUEPRINT.md). Historical records use revisions, idempotent commands and deletion tombstones. Planned meals never count as consumption. Provider errors and missing observations remain distinct from zero values.
