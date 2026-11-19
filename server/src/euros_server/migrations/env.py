"""Alembic environment for the shared PostgreSQL database.

The service runs migrations through euros_server.migrations (euros-server --migrate), which
passes its own connection. The alembic command line, used only to author revisions, connects
with EUROS_DATABASE_URL_FILE, EUROS_DATABASE_URL or EUROS_TEST_DATABASE_URL.
"""
import os

from alembic import context
from sqlalchemy import create_engine, text
from sqlalchemy.pool import NullPool

from euros_server.models import Base

# Held for the whole upgrade so two operators cannot migrate the same database at once.
MIGRATION_LOCK = 4207150612


def _run(connection):
    context.configure(connection=connection, target_metadata=Base.metadata, compare_type=True)
    with context.begin_transaction():
        connection.execute(text(f'SELECT pg_advisory_xact_lock({MIGRATION_LOCK})'))
        context.run_migrations()


def _url():
    from euros_server.database import configured_database_url
    url = configured_database_url() or os.environ.get('EUROS_TEST_DATABASE_URL')
    if not url:
        raise SystemExit('Set EUROS_DATABASE_URL (or EUROS_TEST_DATABASE_URL) to a PostgreSQL database')
    return url.replace('postgresql://', 'postgresql+psycopg://', 1)


if context.is_offline_mode():
    raise SystemExit('Offline SQL generation is not supported; review the revision file instead')
connection = context.config.attributes.get('connection')
if connection is not None:
    _run(connection)
else:
    with create_engine(_url(), poolclass=NullPool).connect() as connection:
        _run(connection)
