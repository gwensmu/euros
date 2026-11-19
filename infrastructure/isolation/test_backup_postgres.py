"""PostgreSQL backups: retention, freshness and failure handling without Docker; and a real
dump, restore and verification when EUROS_BACKUP_TEST_PGPORT names a local server.

  EUROS_BACKUP_TEST_PGPORT=5432 [EUROS_BACKUP_TEST_PGUSER=postgres] \
      python -m unittest discover -s infrastructure/isolation -p 'test_*.py'

The local server needs pg_dump, pg_restore, psql, createdb and dropdb on PATH, and a user
that may create databases. It is used through TCP on 127.0.0.1 and left as it was found.
"""
import datetime
import importlib.util
import json
import os
import stat
import subprocess
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('backup_postgres', ROOT / 'backup-postgres.py')
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)
UTC = datetime.timezone.utc


def at(text):
    return datetime.datetime.strptime(text, '%Y-%m-%d %H:%M').replace(tzinfo=UTC)


def fake_backup(output, moment, verified=True):
    stamp = moment.strftime(backup.STAMP)
    (output / f'euros-{stamp}.dump').write_bytes(b'dump')
    (output / f'euros-{stamp}.globals.sql').write_text('')
    (output / f'euros-{stamp}.json').write_text(json.dumps({'verified': verified, 'dump': f'euros-{stamp}.dump'}))
    return stamp


class RetentionTest(unittest.TestCase):
    def test_keeps_newest_per_day_week_and_month(self):
        times = [at('2026-11-19 03:30'), at('2026-11-19 01:00'), at('2026-11-18 03:30'),
                 at('2026-11-10 03:30'), at('2026-11-03 03:30'), at('2026-10-15 03:30'), at('2026-09-01 03:30')]
        kept = backup.to_keep(times, daily=2, weekly=3, monthly=3)
        self.assertEqual(sorted(kept), sorted([
            at('2026-11-19 03:30'), at('2026-11-18 03:30'),   # Days: the two newest.
            at('2026-11-10 03:30'), at('2026-11-03 03:30'),   # Weeks: with the 19th, the three newest.
            at('2026-10-15 03:30'), at('2026-09-01 03:30')]))  # Months: with the 19th, the three newest.
        # The earlier backup on the 19th is not the newest of any period.

    def test_always_keeps_the_newest(self):
        self.assertEqual(backup.to_keep([at('2026-11-19 03:30')], 1, 0, 0), {at('2026-11-19 03:30')})

    def test_prune_removes_all_files_of_old_backups_and_ignores_unverified(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            newest = fake_backup(output, at('2026-11-19 03:30'))
            old = fake_backup(output, at('2026-11-01 03:30'))
            unverified = fake_backup(output, at('2026-10-01 03:30'), verified=False)
            removed = backup.prune(output, daily=1, weekly=0, monthly=0)
            self.assertEqual(removed, [old])
            remaining = sorted(p.name for p in output.iterdir())
            self.assertFalse([n for n in remaining if old in n])
            self.assertTrue([n for n in remaining if newest in n])
            # Not verified, so never counted as a backup and never pruned as one.
            self.assertTrue([n for n in remaining if unverified in n])

    def test_stale_partials_are_removed_and_fresh_ones_kept(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            stale, fresh = output / 'euros-a.dump.partial', output / 'euros-b.dump.partial'
            stale.write_bytes(b''), fresh.write_bytes(b'')
            os.utime(stale, (0, 0))
            backup.prune(output, 1, 0, 0)
            self.assertFalse(stale.exists())
            self.assertTrue(fresh.exists())


class FreshnessTest(unittest.TestCase):
    def test_check_recent(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            self.assertEqual(backup.main(['--output', directory, '--check-recent', '26']), 1)
            fake_backup(output, datetime.datetime.now(UTC) - datetime.timedelta(hours=30))
            self.assertEqual(backup.main(['--output', directory, '--check-recent', '26']), 1)
            fake_backup(output, datetime.datetime.now(UTC) - datetime.timedelta(hours=2))
            self.assertEqual(backup.main(['--output', directory, '--check-recent', '26']), 0)


class FailureTest(unittest.TestCase):
    def test_a_failed_run_leaves_no_backup_and_prunes_nothing(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'backups'
            output.mkdir()
            kept = fake_backup(output, at('2026-01-01 03:30'))

            def broken(*args, **kwargs):
                raise backup.BackupError('pg_dump failed')
            with patch.object(backup, 'database_image', return_value='postgres:18'), \
                    patch.object(backup, 'back_up', side_effect=broken), \
                    patch.object(backup, 'prune') as prune:
                self.assertEqual(backup.main(['--output', str(output), '--compose', 'x.yaml']), 1)
            prune.assert_not_called()
            self.assertTrue((output / f'euros-{kept}.dump').exists())
            self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o700)


class RestoreCleanupTest(unittest.TestCase):
    def test_start_failure_still_cleans_up_target(self):
        from unittest.mock import Mock
        target = Mock()
        target.start.side_effect = backup.BackupError('startup failed')
        with self.assertRaisesRegex(backup.BackupError, 'startup failed'):
            backup.verify_restore(target, Path('unused.dump'))
        target.stop.assert_called_once_with()


class DockerCommandsTest(unittest.TestCase):
    """The commands sent to Docker, since Docker itself is not available to these tests."""

    def test_production_tools_run_inside_the_database_container(self):
        tools = backup.production_tools(Path('/srv/compose.production.yaml'))
        with patch.object(backup.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, b'', b'')) as run:
            tools.run('pg_dump', '-Fc', '-d', 'euros')
        self.assertEqual(run.call_args.args[0], ['docker', 'compose', '-f', '/srv/compose.production.yaml', 'exec',
                                                 '-T', 'database', 'pg_dump', '-U', 'postgres', '-Fc', '-d', 'euros'])

    def test_restore_check_container_is_isolated_and_removed_with_its_volume(self):
        target = backup.DockerRestoreTarget('postgres:18@sha256:abc', ready_seconds=5)
        answers = iter([1, 0])  # pg_isready: not yet, then ready.
        calls = []

        def run(command, **kwargs):
            calls.append(command)
            code = next(answers) if 'pg_isready' in command else 0
            return subprocess.CompletedProcess(command, code, b'', b'')
        with patch.object(backup.subprocess, 'run', side_effect=run), patch.object(backup.time, 'sleep'):
            target.start()
            target.stop()
        start = calls[0]
        self.assertEqual(start[:2], ['docker', 'run'])
        self.assertIn('--rm', start)
        self.assertEqual(start[start.index('--network') + 1], 'none')
        self.assertEqual(start[start.index('-v') + 1], '/var/lib/postgresql')
        self.assertEqual(start[-1], 'postgres:18@sha256:abc')
        self.assertIn(['docker', 'exec', '-i', target.name, 'createdb', '-h', '127.0.0.1', '-U', 'postgres',
                       'restore_check'], calls)
        self.assertEqual(calls[-1], ['docker', 'rm', '-f', '-v', target.name])

    def test_restore_check_gives_up_when_the_server_never_answers(self):
        target = backup.DockerRestoreTarget('postgres:18', ready_seconds=0)
        with patch.object(backup.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, b'', b'')):
            with self.assertRaisesRegex(backup.BackupError, 'did not start'):
                target.start()

    def test_the_verification_image_is_the_production_database_image(self):
        config = json.dumps({'services': {'database': {'image': 'postgres:18@sha256:abc'}}}).encode()
        with patch.object(backup.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, config, b'')):
            self.assertEqual(backup.database_image(Path('c.yaml')), 'postgres:18@sha256:abc')


@unittest.skipUnless(os.environ.get('EUROS_BACKUP_TEST_PGPORT'), 'Set EUROS_BACKUP_TEST_PGPORT to a local PostgreSQL')
class RealBackupTest(unittest.TestCase):
    def setUp(self):
        self.tools = backup.Tools(connect=['-h', '127.0.0.1', '-p', os.environ['EUROS_BACKUP_TEST_PGPORT']],
                                  user=os.environ.get('EUROS_BACKUP_TEST_PGUSER', 'postgres'))
        self.source = 'backup_source_' + uuid.uuid4().hex[:10]
        self.tools.run('createdb', self.source)
        self.addCleanup(self.tools.run, 'dropdb', '--if-exists', self.source, check=False)
        self.tools.query(self.source, """
            CREATE TABLE users (user_id text PRIMARY KEY, username text UNIQUE NOT NULL);
            CREATE TABLE records (user_id text REFERENCES users, id text, payload text, PRIMARY KEY (user_id, id));
            CREATE TABLE alembic_version (version_num varchar(32) PRIMARY KEY);
            INSERT INTO alembic_version VALUES ('0001');
            INSERT INTO users VALUES ('usr-1', 'athlete'), ('usr-2', 'rival');
            INSERT INTO records SELECT 'usr-1', 'r' || n, repeat('x', 50) FROM generate_series(1, 25) n;""")
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.output = Path(self.directory.name)

    def scratch_databases(self):
        return [row[0] for row in self.tools.query('postgres',
                "SELECT datname FROM pg_database WHERE datname LIKE 'restore_check_%'")]

    def test_dump_restores_and_is_recorded(self):
        now = datetime.datetime(2026, 11, 19, 3, 30, tzinfo=UTC)
        manifest = backup.back_up(self.tools, backup.LocalRestoreTarget(self.tools), self.output, self.source, now)
        self.assertTrue(manifest['verified'])
        self.assertEqual(manifest['schema_revision'], '0001')
        self.assertEqual(manifest['restored_row_counts'],
                         {'public.alembic_version': 1, 'public.records': 25, 'public.users': 2})
        dump = self.output / manifest['dump']
        self.assertEqual(manifest['sha256'], backup.sha256(dump))
        for path in self.output.iterdir():
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600, path.name)
        self.assertEqual(sorted(p.name for p in self.output.iterdir()),
                         ['euros-20261119T033000Z.dump', 'euros-20261119T033000Z.globals.sql',
                          'euros-20261119T033000Z.json'])
        self.assertEqual(self.scratch_databases(), [])
        # The dump is an ordinary pg_dump archive: an operator can restore it by hand.
        listing = subprocess.run(['pg_restore', '--list', str(dump)], capture_output=True, check=True).stdout
        self.assertIn(b'TABLE public records', listing)

    def test_a_dump_that_does_not_restore_is_not_a_backup(self):
        class RefusingTarget(backup.LocalRestoreTarget):
            def start(self):
                super().start()
                # Make the restore collide with an existing table.
                self.tools.query(self.database, 'CREATE TABLE users (x int)')
        with self.assertRaises(backup.BackupError):
            backup.back_up(self.tools, RefusingTarget(self.tools), self.output, self.source)
        self.assertEqual(list(self.output.iterdir()), [])
        self.assertEqual(self.scratch_databases(), [])

    def test_a_restored_revision_that_differs_is_refused(self):
        class Drifting(backup.LocalRestoreTarget):
            pass
        real = backup.schema_revision
        calls = []

        def revision(tools, database):
            calls.append(database)
            return real(tools, database) if len(calls) == 1 else '0002'
        with patch.object(backup, 'schema_revision', side_effect=revision):
            with self.assertRaisesRegex(backup.BackupError, 'revision'):
                backup.back_up(self.tools, Drifting(self.tools), self.output, self.source)
        self.assertEqual(list(self.output.iterdir()), [])

    def test_finds_the_only_application_database_or_asks(self):
        with self.assertRaisesRegex(backup.BackupError, '--database|No database'):
            # The test server has other databases, so it must ask.
            backup.application_database(self.tools)
        self.assertEqual(backup.application_database(self.tools, self.source), self.source)


if __name__ == '__main__':
    unittest.main()
