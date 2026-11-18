"""Explicit, non-destructive migration from personal or per-account database files."""
import json
import os
import sqlite3
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from .database import SCHEMA, LEGACY_OWNER, connect, initialize


def import_training(target, source, user_id):
    source, target = Path(source).resolve(), Path(target).resolve()
    if not source.is_file():
        raise ValueError(f'No database at {source}')
    if source == target:
        raise ValueError('Source and destination must differ')
    # backup() includes committed WAL data and never modifies the source.
    with tempfile.TemporaryDirectory(prefix='.adoption-', dir=target.parent) as tmp:
        snapshot = Path(tmp) / 'source.db'
        with sqlite3.connect(source.as_uri() + '?mode=ro', uri=True) as original, sqlite3.connect(snapshot) as copy:
            original.backup(copy)
        with connect(snapshot) as old, connect(target) as db:
            if old.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
                raise ValueError('Source database integrity check failed')
            tables = {r[0] for r in old.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
            empty_auth = {'sessions', 'login_failures', 'oauth_clients', 'oauth_codes', 'oauth_grants', 'oauth_tokens'}
            for table in tables & empty_auth:
                if old.execute(f'SELECT 1 FROM {table} LIMIT 1').fetchone():
                    raise ValueError('Adoption requires a personal source, not an account registry')
            tables -= empty_auth
            # The source deployment's own spend ledger: token counts, not the person's data, and
            # importing it would charge another deployment's spend to this one's budget.
            tables.discard('model_usage')
            allowed = set(SCHEMA) | {'review_jobs','imported_databases','retired_account_data','users'}
            if 'users' in tables:
                users = old.execute('SELECT user_id, disabled, password_hash FROM users').fetchall()
                if any(tuple(u) != (LEGACY_OWNER, 1, '!') for u in users):
                    raise ValueError('Adoption requires a personal source, not an account registry')
            if tables - allowed:
                raise ValueError('Source contains unsupported tables; refusing to omit data')
            owners = set()
            for table in tables & set(SCHEMA):
                cols = {r['name'] for r in old.execute(f'PRAGMA table_info({table})')}
                if 'user_id' in cols:
                    owners.update(r[0] for r in old.execute(f'SELECT DISTINCT user_id FROM {table}'))
            if len(owners) > 1:
                raise ValueError('Adoption requires a single-account source')
            db.execute('BEGIN IMMEDIATE')
            if db.execute('SELECT 1 FROM users WHERE user_id=?', (user_id,)).fetchone() is None:
                raise ValueError('Destination account does not exist')
            if db.execute('SELECT 1 FROM imported_databases WHERE user_id=?', (user_id,)).fetchone() or any(
                db.execute(f'SELECT 1 FROM {table} WHERE user_id=? LIMIT 1', (user_id,)).fetchone() for table in SCHEMA):
                raise ValueError('Account already has data; import will not overwrite or merge it')
            counts = {}
            for table in tables & set(SCHEMA):
                source_columns = {r['name'] for r in old.execute(f'PRAGMA table_info({table})')}
                columns = [r['name'] for r in db.execute(f'PRAGMA table_info({table})') if r['name'] != 'user_id' and not (table == 'inference_requests' and r['name'] == 'id')]
                if set(columns) - source_columns:
                    raise ValueError('Source table is missing required columns')
                if source_columns - set(columns) - {'user_id'} - ({'id'} if table == 'inference_requests' else set()):
                    raise ValueError('Source contains unsupported columns; refusing to omit data')
                fields = ','.join(columns)
                rows = old.execute(f'SELECT {fields} FROM {table}').fetchall()
                db.executemany(f'INSERT INTO {table}(user_id,{fields}) VALUES ({",".join("?" for _ in range(len(columns)+1))})',
                               [(user_id, *tuple(row)) for row in rows])
                counts[table] = len(rows)
            for table in tables & {'review_jobs','retired_account_data','imported_databases'}:
                for row in old.execute(f'SELECT * FROM {table}'):
                    db.execute('INSERT INTO retired_account_data VALUES (?,?,?)',
                               (user_id, table, json.dumps(dict(row), sort_keys=True)))
            if db.execute('PRAGMA foreign_key_check').fetchone() is not None:
                raise ValueError('Imported database failed foreign key validation')
            db.execute('INSERT INTO imported_databases VALUES (?,?,?)',
                       (user_id,str(source),datetime.now(timezone.utc).isoformat()))
    return counts


def consolidate(data_dir):
    """Run with the old service stopped. Publish the new file only after all users copy.

    Existing accounts.db and users/*/euros.db remain untouched for rollback. No partially
    migrated euros.db is installed on failure. Refuse an existing destination.
    """
    root = Path(data_dir)
    source, target = root / 'accounts.db', root / 'euros.db'
    if target.exists():
        raise ValueError('Shared database already exists; refusing to overwrite it')
    if not source.is_file():
        raise ValueError('No accounts.db to consolidate')
    fd, name = tempfile.mkstemp(prefix='.consolidate-', suffix='.db', dir=root)
    os.close(fd)
    temporary = Path(name)
    try:
        with sqlite3.connect(source.resolve().as_uri() + '?mode=ro', uri=True) as old, sqlite3.connect(temporary) as new:
            old.backup(new)
        initialize(temporary, legacy_owner=None)
        with connect(temporary) as db:
            users = {r[0] for r in db.execute('SELECT user_id FROM users')}
        files = list((root / 'users').glob('*/euros.db'))
        if any(p.parent.name not in users for p in files):
            raise ValueError('Unregistered account database found; refusing to omit it')
        counts = {p.parent.name: import_training(temporary, p, p.parent.name) for p in files}
        with connect(temporary) as db:
            if db.execute('PRAGMA integrity_check').fetchone()[0] != 'ok' or db.execute('PRAGMA foreign_key_check').fetchone() is not None:
                raise ValueError('Consolidated database validation failed')
            db.execute('PRAGMA wal_checkpoint(TRUNCATE)')
        # Hard-link installation refuses a destination created concurrently.
        os.link(temporary, target)
        return {'database':str(target), 'accounts':len(users), 'imported':counts,
                'note':'Source databases were copied, not deleted. Keep them private for rollback.'}
    finally:
        temporary.unlink(missing_ok=True)
        Path(str(temporary)+'-wal').unlink(missing_ok=True)
        Path(str(temporary)+'-shm').unlink(missing_ok=True)
