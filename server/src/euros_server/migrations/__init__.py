"""Versioned schema changes for the shared PostgreSQL database.

Each schema change is a reviewed Alembic revision in versions/. The service never changes an
existing schema on its own. At startup it accepts only a database at the newest revision and
refuses one that is behind, so a release that needs a migration cannot run against the old
schema. The exception is an empty database, which is created at once because there is
nothing in it to lose. An operator applies pending revisions with `euros-server --migrate`
after taking a backup.

A database created before migrations existed has the baseline tables but no version record.
`--migrate` adopts it only if its schema is exactly the baseline's, which it checks by building
the baseline in a temporary schema inside a transaction that is always rolled back.

SQLite, used for local tests and legacy imports, keeps its own upgrade in database.initialize.
"""
import logging
import os
import uuid
from pathlib import Path

from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.config import Config
from alembic.migration import MigrationContext
from alembic.script import ScriptDirectory
from sqlalchemy import MetaData, inspect, text
from sqlalchemy.schema import BLANK_SCHEMA

from ..models import Base

log = logging.getLogger('euros.migrations')
BASELINE = '0001'
ALLOW_NEWER = 'EUROS_ALLOW_NEWER_SCHEMA'
MIGRATION_LOCK = 4207150612


class SchemaNotCurrent(RuntimeError):
    """The database is not at the schema this release expects."""


def _config(connection=None):
    config = Config()
    config.set_main_option('script_location', str(Path(__file__).parent))
    if connection is not None:
        config.attributes['connection'] = connection
    return config


def _scripts():
    return ScriptDirectory.from_config(_config())


def head():
    heads = _scripts().get_heads()
    if len(heads) != 1:
        raise RuntimeError(f'Migrations must have exactly one head, found {sorted(heads)}')
    return heads[0]


def classify(revision, has_tables, head_revision, known):
    """empty, current, behind, unversioned (tables without a version record) or newer."""
    if revision is None:
        return 'unversioned' if has_tables else 'empty'
    if revision == head_revision:
        return 'current'
    return 'behind' if revision in known else 'newer'


def _app_tables(connection):
    return sorted(set(inspect(connection).get_table_names()) & set(Base.metadata.tables))


def _inspect(connection):
    revision = MigrationContext.configure(connection).get_current_revision()
    scripts = _scripts()
    known = {s.revision for s in scripts.walk_revisions()}
    target = head()
    state = classify(revision, bool(_app_tables(connection)), target, known)
    pending = []
    if state in ('empty', 'behind'):
        pending = [s.revision for s in reversed(list(scripts.iterate_revisions(target, revision or 'base')))]
    elif state == 'unversioned':
        pending = [s.revision for s in reversed(list(scripts.iterate_revisions(target, BASELINE)))
                   if s.revision != BASELINE]
    return {'state': state, 'revision': revision, 'head': target, 'pending': pending}


def status(path):
    """Where the database stands, without changing it."""
    from ..database import engine
    with engine(path).connect() as connection:
        return _inspect(connection)


def baseline_differences(connection):
    """How this database's schema differs from the baseline revision's; [] when identical.

    The baseline is built in a throwaway schema on this same connection and rolled back, so
    the comparison uses this server's own types and defaults and leaves nothing behind.
    """
    scratch = 'euros_baseline_' + uuid.uuid4().hex[:12]
    original = connection.execute(text('SELECT current_schema()')).scalar_one()
    outer = connection.begin_nested() if connection.in_transaction() else connection.begin()
    try:
        connection.execute(text(f'CREATE SCHEMA {scratch}'))
        connection.execute(text(f'SET LOCAL search_path TO {scratch}'))
        command.upgrade(_config(connection), BASELINE)
        reflected = MetaData()
        reflected.reflect(connection, schema=scratch)
        baseline = MetaData()
        for table in reflected.tables.values():
            if table.name != 'alembic_version':
                # Unqualified, foreign keys included, so it compares against the live schema.
                table.to_metadata(baseline, schema=None,
                                  referred_schema_fn=lambda *_: BLANK_SCHEMA)
        connection.execute(text(f'SET LOCAL search_path TO {original}'))
        context = MigrationContext.configure(connection, opts={'compare_type': True})
        differences = [repr(d) for d in compare_metadata(context, baseline)]
        # Alembic autogenerate does not compare primary-key or CHECK changes.
        inspector = inspect(connection)
        for table in baseline.tables:
            if not inspector.has_table(table, schema=original):
                continue
            expected_pk = inspector.get_pk_constraint(table, schema=scratch)['constrained_columns']
            actual_pk = inspector.get_pk_constraint(table, schema=original)['constrained_columns']
            if actual_pk != expected_pk:
                differences.append(f'{table}: primary key differs from baseline')
            checks = lambda schema: sorted(c['sqltext'] for c in inspector.get_check_constraints(table, schema=schema))
            if checks(original) != checks(scratch):
                differences.append(f'{table}: CHECK constraints differ from baseline')
        return differences
    finally:
        outer.rollback()


def upgrade(path):
    """Apply every pending revision; adopt a pre-migration database at the baseline first.

    Returns the status before and after. Refuses a database newer than this release and one
    whose unversioned schema is not exactly the baseline.
    """
    from ..database import engine
    with engine(path).begin() as connection:
        # Acquire before inspection/adoption and hold until all revisions commit together.
        connection.execute(text(f'SELECT pg_advisory_xact_lock({MIGRATION_LOCK})'))
        before = _inspect(connection)
        if before['state'] == 'newer':
            raise SchemaNotCurrent(f"The database is at revision {before['revision']}, which this release "
                                   f"does not know (newest here: {before['head']}). Deploy the newer release.")
        if before['state'] == 'unversioned':
            differences = baseline_differences(connection)
            if differences:
                raise SchemaNotCurrent('The existing schema differs from the migration baseline; nothing was '
                                       'changed. Differences: ' + '; '.join(differences))
            command.stamp(_config(connection), BASELINE)
            log.warning('Adopted the existing schema at the migration baseline %s', BASELINE)
        if before['state'] != 'current':
            command.upgrade(_config(connection), 'head')
        after = _inspect(connection)
    return {'before': before, 'after': after}


def ensure_current(path):
    """The startup check. Creates an empty database; refuses one that is behind or unversioned."""
    found = status(path)
    if found['state'] == 'current':
        return found
    if found['state'] == 'empty':
        return upgrade(path)['after']
    if found['state'] == 'newer':
        if os.environ.get(ALLOW_NEWER) == '1':
            log.warning('Running against schema revision %s, newer than this release (%s), because %s=1',
                        found['revision'], found['head'], ALLOW_NEWER)
            return found
        raise SchemaNotCurrent(f"The database is at schema revision {found['revision']}, newer than this "
                               f"release ({found['head']}). Deploy the newer release, or set {ALLOW_NEWER}=1 "
                               f"to run this one against it after confirming its changes are additive.")
    raise SchemaNotCurrent(f"The database schema is {found['state']} (revision {found['revision']}, this release "
                           f"needs {found['head']}). Back it up, then run `euros-server --migrate`.")
