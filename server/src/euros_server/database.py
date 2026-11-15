"""SQLAlchemy engines and transaction-scoped account sessions; raw access is for admin/migrations."""
import sqlite3
from contextlib import contextmanager
from functools import lru_cache
from pathlib import Path

from sqlalchemy import create_engine, event, inspect
from sqlalchemy.dialects.sqlite import dialect
from sqlalchemy.orm import Session, with_loader_criteria
from sqlalchemy.pool import NullPool
from sqlalchemy.schema import CreateTable, CreateIndex

from .models import Base, Owned, User, OWNED_TABLES

LEGACY_OWNER = 'usr-' + '0' * 32
SCHEMA = OWNED_TABLES


@lru_cache(maxsize=32)
def engine(path):
    result = create_engine('sqlite:///' + str(Path(path).resolve()), poolclass=NullPool,
                           connect_args={'timeout':10})
    @event.listens_for(result, 'connect')
    def configure(db, _):
        db.execute('PRAGMA foreign_keys=ON')
    return result


class AccountSession(Session):
    """A normal ORM session with a mandatory, immutable account context."""
    def __init__(self, *args, user_id, **kwargs):
        super().__init__(*args, **kwargs)
        self._user_id = user_id

    @property
    def user_id(self):
        return self._user_id


@event.listens_for(AccountSession, 'do_orm_execute')
def scope_statements(state):
    # Domain services use mapped entities. Raw SQL and bulk inserts could bypass ownership.
    if (not state.is_orm_statement or state.is_insert or state.is_update
            or not state.all_mappers or any(not issubclass(m.class_, Owned) for m in state.all_mappers)):
        raise ValueError('Account sessions require owned ORM reads/deletes and instance writes')
    if state.is_select or state.is_update or state.is_delete:
        uid = state.session.user_id
        state.statement = state.statement.options(with_loader_criteria(Owned, lambda cls: cls.user_id == uid, include_aliases=True))


@event.listens_for(AccountSession, 'before_flush')
def scope_writes(session, *_):
    for obj in session.new | session.dirty | session.deleted:
        if not isinstance(obj, Owned) or obj.user_id != session.user_id:
            raise ValueError('Cannot write another account or authentication data through an account session')
        history = inspect(obj).attrs.user_id.history
        if history.deleted and any(old != session.user_id for old in history.deleted):
            raise ValueError('Account ownership is immutable')


@contextmanager
def account_session(path, user_id, write=False):
    # SQLite serializes writers. Lock before reading revisions or allocating account cursors.
    with engine(path).connect() as connection:
        connection.exec_driver_sql('BEGIN IMMEDIATE' if write else 'BEGIN')
        try:
            with AccountSession(bind=connection, user_id=user_id, expire_on_commit=False) as session:
                yield session
                session.flush()
            connection.commit()
        except BaseException:
            connection.rollback()
            raise


@contextmanager
def connect(path):
    """Unscoped operator connection. Application account queries use account_session."""
    db = sqlite3.connect(str(path), timeout=10)
    db.row_factory = sqlite3.Row
    db.execute('PRAGMA foreign_keys=ON')
    try:
        with db:
            yield db
    finally:
        db.close()


def initialize(path, legacy_owner=LEGACY_OWNER):
    """Atomic v1 upgrade; all new table definitions come from SQLAlchemy metadata."""
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with connect(path) as db:
        version = db.execute('PRAGMA user_version').fetchone()[0]
        if version not in (0,1,2):
            raise RuntimeError(f'Unsupported database schema {version}')
        db.execute('PRAGMA journal_mode=WAL')
        db.execute('BEGIN IMMEDIATE')
        db.execute(str(CreateTable(User.__table__, if_not_exists=True).compile(dialect=dialect())))
        if legacy_owner == LEGACY_OWNER:
            db.execute("INSERT OR IGNORE INTO users VALUES (?, '__legacy__', 'Personal installation', '!', '', '', 1)", (LEGACY_OWNER,))
        elif legacy_owner is not None and not db.execute('SELECT 1 FROM users WHERE user_id=?',(legacy_owner,)).fetchone():
            raise ValueError('Unknown account')
        for table in OWNED_TABLES.values():
            name=table.name
            columns=[r['name'] for r in db.execute(f'PRAGMA table_info({name})')]
            if columns and 'user_id' not in columns:
                if legacy_owner is None:
                    raise ValueError('Use explicit database adoption for legacy training records')
                db.execute(f'ALTER TABLE {name} RENAME TO migration_{name}')
            db.execute(str(CreateTable(table, if_not_exists=True).compile(dialect=dialect())))
            if columns and 'user_id' not in columns:
                fields=','.join(columns)
                db.execute(f'INSERT INTO {name}(user_id,{fields}) SELECT ?,{fields} FROM migration_{name}',(legacy_owner,))
                db.execute(f'DROP TABLE migration_{name}')
            for index in table.indexes:
                db.execute(str(CreateIndex(index,if_not_exists=True).compile(dialect=dialect())))
        db.execute('CREATE TABLE IF NOT EXISTS imported_databases (user_id TEXT PRIMARY KEY REFERENCES users(user_id), source TEXT NOT NULL, imported_at TEXT NOT NULL)')
        db.execute('CREATE TABLE IF NOT EXISTS retired_account_data (user_id TEXT NOT NULL REFERENCES users(user_id), table_name TEXT NOT NULL, row_json TEXT NOT NULL)')
        db.execute('PRAGMA user_version=2')
