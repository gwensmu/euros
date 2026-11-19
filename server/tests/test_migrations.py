"""Versioned PostgreSQL schema: the models match the migrations, and startup never changes an
existing schema on its own."""
import json
import sys

import pytest
from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.migration import MigrationContext
from sqlalchemy import text

from euros_server import migrations
from euros_server.database import engine, initialize
from euros_server.migrations import BASELINE, SchemaNotCurrent, classify
from euros_server.models import Base
from test_postgres import pg  # noqa: F401 - fixture: a fresh schema, or skipped without PostgreSQL


# --- without a database ----------------------------------------------------------------

def test_one_head_descending_from_the_baseline():
    scripts = migrations._scripts()
    assert len(scripts.get_heads()) == 1
    chain = [s.revision for s in scripts.walk_revisions()]
    assert chain[-1] == BASELINE and scripts.get_revision(BASELINE).down_revision is None


@pytest.mark.parametrize('revision,tables,expected', [
    (None, False, 'empty'), (None, True, 'unversioned'), ('0002', True, 'current'),
    ('0001', True, 'behind'), ('9999', True, 'newer')])
def test_classify(revision, tables, expected):
    assert classify(revision, tables, '0002', {'0001', '0002'}) == expected


def test_the_baseline_cannot_be_downgraded():
    module = migrations._scripts().get_revision(BASELINE).module
    with pytest.raises(RuntimeError, match='restore a backup'):
        module.downgrade()


def test_migrate_refuses_sqlite(tmp_path, monkeypatch, capsys):
    from euros_server import app
    monkeypatch.delenv('EUROS_DATABASE_URL_FILE', raising=False)
    monkeypatch.delenv('EUROS_DATABASE_URL', raising=False)
    monkeypatch.setenv('EUROS_DATA_DIR', str(tmp_path))
    monkeypatch.setattr(sys, 'argv', ['euros-server', '--migrate'])
    with pytest.raises(ValueError, match='PostgreSQL'):
        app.main()


# --- against PostgreSQL ----------------------------------------------------------------

def differences_from_models(url):
    with engine(url).connect() as connection:
        context = MigrationContext.configure(connection, opts={'compare_type': True})
        return compare_metadata(context, Base.metadata)


def scratch_schemas(url):
    with engine(url).connect() as connection:
        return connection.execute(text(
            "SELECT nspname FROM pg_namespace WHERE nspname LIKE 'euros_baseline_%'")).scalars().all()


def test_migrations_build_exactly_the_models(pg):
    """The check that catches a model change without a migration."""
    initialize(pg, legacy_owner=None)
    assert migrations.status(pg)['state'] == 'current'
    assert differences_from_models(pg) == []


def test_an_empty_database_is_created_at_startup(pg):
    assert migrations.status(pg)['state'] == 'empty'
    initialize(pg, legacy_owner=None)
    found = migrations.status(pg)
    assert found['state'] == 'current' and found['revision'] == found['head'] and found['pending'] == []


def adopt_pre_migration_database(url):
    """What production holds today: tables from create_all and no version record."""
    Base.metadata.create_all(engine(url))
    with engine(url).begin() as db:
        db.execute(text("INSERT INTO users VALUES ('usr-1', 'athlete', 'Athlete', '!', '', '', 0)"))


def test_startup_refuses_an_unversioned_database_and_changes_nothing(pg):
    adopt_pre_migration_database(pg)
    with pytest.raises(SchemaNotCurrent, match='--migrate'):
        initialize(pg, legacy_owner=None)
    assert migrations.status(pg)['state'] == 'unversioned'


def test_migrate_adopts_a_matching_unversioned_database_and_keeps_its_rows(pg):
    adopt_pre_migration_database(pg)
    result = migrations.upgrade(pg)
    assert result['before']['state'] == 'unversioned' and result['after']['state'] == 'current'
    with engine(pg).connect() as db:
        assert db.execute(text('SELECT username FROM users')).scalars().all() == ['athlete']
    assert scratch_schemas(pg) == []
    initialize(pg, legacy_owner=None)  # Starts normally afterwards.
    assert migrations.upgrade(pg)['before']['state'] == 'current'  # Idempotent.


def test_migrate_refuses_an_unversioned_database_that_differs_from_the_baseline(pg):
    adopt_pre_migration_database(pg)
    with engine(pg).begin() as db:
        db.execute(text('ALTER TABLE users ADD COLUMN nickname TEXT'))
    with pytest.raises(SchemaNotCurrent, match='nickname'):
        migrations.upgrade(pg)
    assert migrations.status(pg)['state'] == 'unversioned'
    assert scratch_schemas(pg) == []


def test_a_newer_database_is_refused_unless_explicitly_allowed(pg, monkeypatch):
    initialize(pg, legacy_owner=None)
    with engine(pg).begin() as db:
        db.execute(text("UPDATE alembic_version SET version_num = 'ffff'"))
    with pytest.raises(SchemaNotCurrent, match='newer'):
        initialize(pg, legacy_owner=None)
    with pytest.raises(SchemaNotCurrent, match='newer release'):
        migrations.upgrade(pg)
    monkeypatch.setenv(migrations.ALLOW_NEWER, '1')
    initialize(pg, legacy_owner=None)


def test_a_database_behind_this_release_is_refused_then_migrated(pg, tmp_path, monkeypatch):
    """A second revision in a scratch copy of the scripts stands in for the next real one."""
    import shutil
    from pathlib import Path
    source = Path(migrations.__file__).parent
    scripts = tmp_path / 'migrations'
    shutil.copytree(source, scripts, ignore=shutil.ignore_patterns('__pycache__'))
    (scripts / 'versions' / '9000_add_nickname.py').write_text(f'''
from alembic import op
import sqlalchemy as sa
revision = '9000'
down_revision = {migrations.head()!r}
branch_labels = None
depends_on = None
def upgrade():
    op.add_column('users', sa.Column('nickname', sa.Text(), nullable=True))
def downgrade():
    op.drop_column('users', 'nickname')
''')
    initialize(pg, legacy_owner=None)
    monkeypatch.setattr(migrations, '__file__', str(scripts / '__init__.py'))
    found = migrations.status(pg)
    assert found['state'] == 'behind' and found['pending'] == ['9000']
    with pytest.raises(SchemaNotCurrent, match='--migrate'):
        migrations.ensure_current(pg)
    assert migrations.upgrade(pg)['after']['state'] == 'current'
    with engine(pg).connect() as db:
        assert 'nickname' in db.execute(text(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'users' "
            "AND table_schema = current_schema()")).scalars().all()


def test_cli_status_and_migrate(pg, monkeypatch, capsys):
    from euros_server import app
    monkeypatch.delenv('EUROS_DATABASE_URL_FILE', raising=False)
    monkeypatch.setenv('EUROS_DATABASE_URL', pg)
    adopt_pre_migration_database(pg)
    monkeypatch.setattr(sys, 'argv', ['euros-server', '--migration-status'])
    app.main()
    assert json.loads(capsys.readouterr().out)['state'] == 'unversioned'
    monkeypatch.setattr(sys, 'argv', ['euros-server', '--migrate'])
    app.main()
    assert json.loads(capsys.readouterr().out)['after']['state'] == 'current'


def test_alembic_command_line_config_finds_the_same_scripts():
    from pathlib import Path
    from alembic.config import Config
    from alembic.script import ScriptDirectory
    config = Config(str(Path(__file__).parents[1] / 'alembic.ini'))
    assert ScriptDirectory.from_config(config).get_heads() == migrations._scripts().get_heads()


@pytest.mark.parametrize('change', [
    'ALTER TABLE training_plan ADD CONSTRAINT extra_check CHECK (revision > 0)',
    'ALTER TABLE login_failures ADD PRIMARY KEY (username, at)',
])
def test_adoption_refuses_constraint_drift(pg, change):
    adopt_pre_migration_database(pg)
    with engine(pg).begin() as db:
        db.execute(text(change))
    with pytest.raises(SchemaNotCurrent, match='differs|constraints'):
        migrations.upgrade(pg)
    assert migrations.status(pg)['state'] == 'unversioned'


def test_failed_upgrade_rolls_back_baseline_adoption(pg, monkeypatch):
    adopt_pre_migration_database(pg)
    real_upgrade = command.upgrade
    def fail_after_adoption(config, revision):
        if revision == 'head':
            config.attributes['connection'].execute(text('CREATE TABLE should_rollback (id integer)'))
            raise RuntimeError('failed revision')
        return real_upgrade(config, revision)
    monkeypatch.setattr(command, 'upgrade', fail_after_adoption)
    with pytest.raises(RuntimeError, match='failed revision'):
        migrations.upgrade(pg)
    assert migrations.status(pg)['state'] == 'unversioned'
    with engine(pg).connect() as db:
        assert db.execute(text("SELECT to_regclass('should_rollback')")).scalar() is None
        assert db.execute(text('SELECT username FROM users')).scalars().all() == ['athlete']


def test_concurrent_adoptions_serialize_and_preserve_rows(pg):
    from concurrent.futures import ThreadPoolExecutor
    adopt_pre_migration_database(pg)
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(migrations.upgrade, [pg, pg]))
    assert all(result['after']['state'] == 'current' for result in results)
    assert sorted(result['before']['state'] for result in results) == ['current', 'unversioned']
    assert scratch_schemas(pg) == []
