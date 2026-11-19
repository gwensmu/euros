#!/usr/bin/env python3
"""Back up the production PostgreSQL database, prove the backup restores, and prune old ones.

Runs on the deployment host with its own Python (standard library only) and Docker:

  backup-postgres.py                     back up, verify, prune; exit 1 on any failure
  backup-postgres.py --check-recent 26   exit 1 unless a verified backup is under 26 hours old

Each run:
1. Dumps the application database with `pg_dump -Fc` from inside the database container, and
   the cluster's roles with `pg_dumpall --globals-only --no-role-passwords`. Nothing is copied
   out of the data volume while PostgreSQL runs; pg_dump reads one consistent snapshot.
2. Restores the dump into a disposable PostgreSQL container started from the same image, with
   no network, using `pg_restore --exit-on-error`, then counts every table's rows and reads the
   schema revision there. A dump that does not restore is not a backup.
3. Only then renames the files into place and writes the manifest (checksum, size, schema
   revision, restored row counts). A backup without a manifest is not a verified backup.
4. Prunes verified backups beyond the retention policy (newest per day, ISO week and month).
   A failed run prunes nothing, so a broken backup job never deletes good backups.

Backups hold every account's data and password verifiers: files are owner-only (0600) in an
owner-only directory. They stay on this host; copying them elsewhere is a separate decision.
Never restore over the live database; see server/POSTGRES.md, "Backup and recovery".
"""
import argparse
import contextlib
import datetime
import fcntl
import hashlib
import json
import os
import subprocess
import sys
import time
import uuid
from pathlib import Path

ROOT = Path(os.environ.get('EUROS_DEPLOYMENT_ROOT', '.')).resolve()
PREFIX = 'euros-'
STAMP = '%Y%m%dT%H%M%SZ'
SUPERUSER = 'postgres'
SYSTEM_DATABASES = {'postgres', 'template0', 'template1'}
# Leftovers of an interrupted run older than this are removed.
STALE_PARTIAL_SECONDS = 6 * 3600
COUNT_QUERY = """
SELECT table_schema || '.' || table_name,
       (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I',
                                                    table_schema, table_name), false, true, '')))[1]::text
FROM information_schema.tables
WHERE table_type = 'BASE TABLE' AND table_schema NOT IN ('pg_catalog', 'information_schema')
ORDER BY 1
"""


class BackupError(RuntimeError):
    pass


class Tools:
    """PostgreSQL client programs, run inside a container (prefix) or locally (no prefix)."""

    def __init__(self, prefix=(), connect=(), user=SUPERUSER, env=None):
        self.prefix, self.connect, self.user, self.env = list(prefix), list(connect), user, env

    def run(self, program, *args, stdin=None, stdout=None, check=True):
        command = [*self.prefix, program, *self.connect, '-U', self.user, *args]
        result = subprocess.run(command, stdin=stdin, stdout=stdout if stdout is not None else subprocess.PIPE,
                                stderr=subprocess.PIPE, env=self.env)
        if check and result.returncode != 0:
            raise BackupError(f'{program} failed ({result.returncode}): '
                              f'{result.stderr.decode(errors="replace").strip()[-2000:]}')
        return result

    def query(self, database, sql, check=True):
        result = self.run('psql', '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-A', '-t', '-F', '\t',
                          '-d', database, '-c', sql, check=check)
        if result.returncode != 0:
            return None
        return [line.split('\t') for line in result.stdout.decode().splitlines() if line]


# --- where the database lives -----------------------------------------------------------

def compose_command(compose):
    return ['docker', 'compose', '-f', str(compose)]


def production_tools(compose):
    # Inside the database container, as its superuser over the local socket.
    return Tools([*compose_command(compose), 'exec', '-T', 'database'])


def database_image(compose):
    config = subprocess.run([*compose_command(compose), 'config', '--format', 'json'],
                            capture_output=True, check=True)
    return json.loads(config.stdout)['services']['database']['image']


class DockerRestoreTarget:
    """A throwaway PostgreSQL server from the production image: no network, an anonymous
    volume, removed with that volume afterwards."""

    database = 'restore_check'

    def __init__(self, image, ready_seconds=90):
        self.image, self.ready_seconds = image, ready_seconds
        self.name = 'euros-restore-check-' + uuid.uuid4().hex[:12]
        # Over loopback TCP: the image's first-start initialization server listens only on
        # its socket, so a TCP answer means the real server is up.
        self.tools = Tools(['docker', 'exec', '-i', self.name], ['-h', '127.0.0.1'])

    def start(self):
        subprocess.run(['docker', 'run', '-d', '--rm', '--name', self.name, '--network', 'none',
                        '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', '-v', '/var/lib/postgresql', self.image],
                       capture_output=True, check=True)
        deadline = time.monotonic() + self.ready_seconds
        while time.monotonic() < deadline:
            if self.tools.run('pg_isready', check=False).returncode == 0:
                self.tools.run('createdb', self.database)
                return
            time.sleep(1)
        raise BackupError('The restore-check server did not start')

    def stop(self):
        subprocess.run(['docker', 'rm', '-f', '-v', self.name], capture_output=True)


class LocalRestoreTarget:
    """A scratch database on an existing server; for tests and manual rehearsal."""

    def __init__(self, tools):
        self.tools, self.database = tools, 'restore_check_' + uuid.uuid4().hex[:12]

    def start(self):
        self.tools.run('createdb', self.database)

    def stop(self):
        self.tools.run('dropdb', '--if-exists', self.database, check=False)


# --- one backup -------------------------------------------------------------------------

def application_database(tools, requested=None):
    names = {row[0] for row in tools.query('postgres', 'SELECT datname FROM pg_database WHERE datallowconn')}
    candidates = sorted(names - SYSTEM_DATABASES)
    if requested:
        if requested not in names:
            raise BackupError(f'No database named {requested}')
        return requested
    if len(candidates) != 1:
        raise BackupError(f'Choose the database with --database; found {candidates or "none"}')
    return candidates[0]


def schema_revision(tools, database):
    rows = tools.query(database, 'SELECT version_num FROM alembic_version', check=False)
    return rows[0][0] if rows else None


def private_file(path):
    return open(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb')


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as source:
        for block in iter(lambda: source.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def verify_restore(target, dump):
    """Restore into the target and report what came back, or raise."""
    try:
        target.start()
        with open(dump, 'rb') as source:
            target.tools.run('pg_restore', '--exit-on-error', '--no-owner', '--no-privileges',
                             '-d', target.database, stdin=source)
        counts = {name: int(count) for name, count in target.tools.query(target.database, COUNT_QUERY)}
        return counts, schema_revision(target.tools, target.database)
    finally:
        target.stop()


def back_up(tools, target, output, database=None, now=None):
    now = now or datetime.datetime.now(datetime.timezone.utc)
    stamp = now.strftime(STAMP)
    name = application_database(tools, database)
    dump, roles = output / f'{PREFIX}{stamp}.dump', output / f'{PREFIX}{stamp}.globals.sql'
    partial_dump, partial_roles = Path(f'{dump}.partial'), Path(f'{roles}.partial')
    try:
        with private_file(partial_dump) as file:
            tools.run('pg_dump', '-Fc', '-d', name, stdout=file)
            os.fsync(file.fileno())
        with private_file(partial_roles) as file:
            tools.run('pg_dumpall', '--globals-only', '--no-role-passwords', stdout=file)
            os.fsync(file.fileno())
        revision = schema_revision(tools, name)
        counts, restored_revision = verify_restore(target, partial_dump)
        if not counts:
            raise BackupError('The restored database has no tables')
        if restored_revision != revision:
            raise BackupError(f'Restored schema revision {restored_revision} differs from the source {revision}')
        manifest = {'created_at': now.isoformat(), 'database': name, 'dump': dump.name, 'globals': roles.name,
                    'bytes': partial_dump.stat().st_size, 'sha256': sha256(partial_dump),
                    'schema_revision': revision, 'verified': True, 'restored_row_counts': counts}
        partial_dump.rename(dump)
        partial_roles.rename(roles)
        manifest_path = output / f'{PREFIX}{stamp}.json'
        with private_file(manifest_path) as file:
            file.write(json.dumps(manifest, indent=2, sort_keys=True).encode())
            os.fsync(file.fileno())
        return manifest
    finally:
        for leftover in (partial_dump, partial_roles):
            leftover.unlink(missing_ok=True)


# --- retention --------------------------------------------------------------------------

def verified_backups(output):
    """{created_at: stamp} for every backup with a verified manifest."""
    found = {}
    for manifest in output.glob(f'{PREFIX}*.json'):
        try:
            data = json.loads(manifest.read_text())
            stamp = manifest.name[len(PREFIX):-len('.json')]
            created = datetime.datetime.strptime(stamp, STAMP).replace(tzinfo=datetime.timezone.utc)
        except (ValueError, OSError):
            continue
        if data.get('verified') is True and (output / data.get('dump', '')).is_file():
            found[created] = stamp
    return found


def to_keep(times, daily, weekly, monthly):
    """Grandfather-father-son: the newest backup of each of the newest `daily` days, `weekly`
    ISO weeks and `monthly` months, and always the newest backup."""
    ordered = sorted(times, reverse=True)
    kept = set(ordered[:1])
    for count, period in ((daily, lambda t: t.date()), (weekly, lambda t: t.isocalendar()[:2]),
                          (monthly, lambda t: (t.year, t.month))):
        seen = []
        for moment in ordered:
            key = period(moment)
            if key not in seen and len(seen) < count:
                seen.append(key)
                kept.add(moment)
    return kept


def prune(output, daily, weekly, monthly, now=None):
    backups = verified_backups(output)
    keep = to_keep(backups, daily, weekly, monthly)
    removed = []
    for moment, stamp in sorted(backups.items()):
        if moment not in keep:
            for suffix in ('.dump', '.globals.sql', '.json'):
                (output / f'{PREFIX}{stamp}{suffix}').unlink(missing_ok=True)
            removed.append(stamp)
    cutoff = (now or time.time()) - STALE_PARTIAL_SECONDS
    for leftover in output.glob(f'{PREFIX}*.partial'):
        if leftover.stat().st_mtime < cutoff:
            leftover.unlink(missing_ok=True)
    return removed


def newest_age_hours(output, now=None):
    backups = verified_backups(output)
    if not backups:
        return None
    now = now or datetime.datetime.now(datetime.timezone.utc)
    return (now - max(backups)).total_seconds() / 3600


@contextlib.contextmanager
def exclusive(output):
    with open(output / '.lock', 'w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise BackupError('Another backup is running') from None
        yield


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('--compose', type=Path, default=ROOT / 'compose.production.yaml')
    parser.add_argument('--output', type=Path, default=ROOT / 'backups/postgres')
    parser.add_argument('--database', help='Application database name (found automatically when there is one)')
    parser.add_argument('--keep-daily', type=int, default=14)
    parser.add_argument('--keep-weekly', type=int, default=8)
    parser.add_argument('--keep-monthly', type=int, default=12)
    parser.add_argument('--check-recent', type=float, metavar='HOURS',
                        help='Only check that a verified backup is newer than HOURS; exit 1 if not')
    args = parser.parse_args(argv)
    os.umask(0o077)
    if args.check_recent is not None:
        age = newest_age_hours(args.output)
        fresh = age is not None and age <= args.check_recent
        print(json.dumps({'fresh': fresh, 'newest_age_hours': None if age is None else round(age, 1)}))
        return 0 if fresh else 1
    if min(args.keep_daily, args.keep_weekly, args.keep_monthly) < 0 or args.keep_daily < 1:
        parser.error('Keep at least one daily backup; counts cannot be negative')
    args.output.mkdir(parents=True, exist_ok=True, mode=0o700)
    args.output.chmod(0o700)
    try:
        with exclusive(args.output):
            target = DockerRestoreTarget(database_image(args.compose))
            manifest = back_up(production_tools(args.compose), target, args.output, args.database)
            removed = prune(args.output, args.keep_daily, args.keep_weekly, args.keep_monthly)
    except (BackupError, subprocess.CalledProcessError, OSError) as exc:
        detail = exc.stderr.decode(errors='replace')[-2000:] if isinstance(exc, subprocess.CalledProcessError) and exc.stderr else ''
        print(json.dumps({'ok': False, 'error': str(exc), 'detail': detail}), file=sys.stderr)
        return 1
    print(json.dumps({'ok': True, 'dump': manifest['dump'], 'bytes': manifest['bytes'],
                      'schema_revision': manifest['schema_revision'],
                      'tables': len(manifest['restored_row_counts']), 'pruned': removed}))
    return 0


if __name__ == '__main__':
    sys.exit(main())
