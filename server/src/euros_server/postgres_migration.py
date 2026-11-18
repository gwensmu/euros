"""Copy a stopped personal SQLite snapshot into PostgreSQL, verifying every source row.

The source is opened immutable/read-only. Callers must first use SQLite backup() to produce
that standalone snapshot, or stop the old service and checkpoint it. No user records are
written until all source tables have passed schema checks. A failed copy rolls back wholly.
"""
import hashlib
import json
import sqlite3
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from sqlalchemy import select, insert, func
from .database import is_postgres, transaction, LEGACY_OWNER
from .models import User, OWNED_TABLES, imported_databases, retired_account_data


def fingerprint(row):
    return hashlib.sha256(json.dumps(dict(row), sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def import_snapshot(url, source, user_id=LEGACY_OWNER):
    if not is_postgres(url):
        raise ValueError('PostgreSQL destination required')
    path = Path(source).resolve(strict=True)
    wal = Path(str(path) + '-wal')
    if wal.exists() and wal.stat().st_size:
        raise ValueError('Use a standalone SQLite backup snapshot, not a live WAL database')
    with sqlite3.connect(path.as_uri() + '?mode=ro&immutable=1', uri=True) as old:
        old.row_factory = sqlite3.Row
        if old.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
            raise ValueError('Source integrity check failed')
        names = {r[0] for r in old.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
        names.discard('model_usage')  # another deployment's spend ledger, not account data
        if names - set(OWNED_TABLES) - {'review_jobs'}:
            raise ValueError('Unexpected source tables; refusing to omit data')
        source_columns = {}
        for name in names - {'review_jobs'}:
            columns = [r['name'] for r in old.execute(f'PRAGMA table_info({name})')]
            expected = set(OWNED_TABLES[name].c.keys()) - {'user_id'}
            if name == 'inference_requests':
                expected -= {'id'}
            if set(columns) != expected:
                raise ValueError(f'Unexpected source columns in {name}')
            source_columns[name] = columns
        with transaction(url, write=True) as db:
            db.execute(select(User.user_id).where(User.user_id == user_id).with_for_update()).scalar_one()
            if db.scalar(select(func.count()).select_from(imported_databases).where(imported_databases.c.user_id == user_id)):
                raise ValueError('Account was already imported')
            for table in OWNED_TABLES.values():
                if db.scalar(select(func.count()).select_from(table).where(table.c.user_id == user_id)):
                    raise ValueError('Destination account already has data')
            if db.scalar(select(func.count()).select_from(retired_account_data).where(retired_account_data.c.user_id == user_id)):
                raise ValueError('Destination archive already has data')
            report = {}
            for name, columns in source_columns.items():
                table = OWNED_TABLES[name]
                expected = Counter()
                for row in old.execute(f'SELECT * FROM {name}'):
                    data = dict(row)
                    expected[fingerprint(data)] += 1
                    db.execute(insert(table).values(user_id=user_id, **data))
                actual = Counter(fingerprint(row) for row in db.execute(select(*(table.c[c] for c in columns)).where(table.c.user_id == user_id)).mappings())
                if actual != expected:
                    raise ValueError(f'Row verification failed for {name}')
                report[name] = {'rows': sum(expected.values()), 'verified': True}
            if 'review_jobs' in names:
                expected = Counter()
                for row in old.execute('SELECT * FROM review_jobs'):
                    body = json.dumps(dict(row), sort_keys=True)
                    expected[hashlib.sha256(body.encode()).hexdigest()] += 1
                    db.execute(insert(retired_account_data).values(user_id=user_id, table_name='review_jobs', row_json=body))
                actual = Counter(hashlib.sha256(body.encode()).hexdigest() for body in db.scalars(select(retired_account_data.c.row_json).where(retired_account_data.c.user_id == user_id, retired_account_data.c.table_name == 'review_jobs')))
                if actual != expected:
                    raise ValueError('Historical review verification failed')
                report['review_jobs'] = {'rows':sum(expected.values()), 'verified':True, 'archived':True}
            db.execute(insert(imported_databases).values(user_id=user_id, source=str(path), imported_at=datetime.now(timezone.utc).isoformat()))
            return report
