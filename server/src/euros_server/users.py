"""Who may sign in: account identities, their passwords and sign-in throttling.

A user row is the only place an account exists. Training records share the same database,
with account-scoped domain access (workspace.py); this store only manages identities.
"""
import re
import sqlite3
import time
import uuid
from datetime import datetime, timezone

from .credentials import ITERATIONS, hash_password, validate_password, verify_password
from .domain import DomainError

USERNAME = re.compile(r'[a-z0-9][a-z0-9._-]{2,31}')
# Failed sign-ins per username within the window before the account stops answering attempts.
MAX_FAILURES, FAILURE_WINDOW = 10, 900


def validate_username(value):
    if not isinstance(value, str) or not USERNAME.fullmatch(value.strip().lower()):
        raise DomainError('invalid_username', 422,
                          rule='3-32 characters: lowercase letters, digits, dot, dash or underscore')
    return value.strip().lower()


def validate_display_name(value, username):
    if value is None or (isinstance(value, str) and not value.strip()):
        return username
    if not isinstance(value, str) or len(value.strip()) > 80:
        raise DomainError('invalid_display_name', 422)
    return value.strip()


class UserStore:
    """Accounts and passwords in the shared database."""

    def __init__(self, database, iterations=ITERATIONS):
        self.database, self.iterations = database, iterations
        with database.connect() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS users (
                  user_id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL,
                  password_hash TEXT NOT NULL, created_at TEXT NOT NULL, password_changed_at TEXT NOT NULL,
                  disabled INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS login_failures (username TEXT NOT NULL, at REAL NOT NULL);
                CREATE INDEX IF NOT EXISTS login_failures_at ON login_failures(at);
            ''')

    @staticmethod
    def account(row):
        """A user row as the rest of the service sees it: never the password verifier."""
        if row is None:
            return None
        return dict(user_id=row['user_id'], username=row['username'], display_name=row['display_name'],
                    created_at=row['created_at'], password_changed_at=row['password_changed_at'],
                    disabled=bool(row['disabled']))

    def create(self, username, password, display_name=None):
        username = validate_username(username)
        validate_password(password)
        display = validate_display_name(display_name, username)
        stamp = datetime.now(timezone.utc).isoformat()
        user_id = 'usr-' + uuid.uuid4().hex
        try:
            with self.database.connect() as db:
                db.execute('INSERT INTO users VALUES (?,?,?,?,?,?,0)',
                           (user_id, username, display, hash_password(password, self.iterations), stamp, stamp))
        except sqlite3.IntegrityError:
            raise DomainError('username_taken', 409) from None
        return self.read(user_id)

    def read(self, user_id):
        with self.database.connect() as db:
            return self.account(db.execute('SELECT * FROM users WHERE user_id=?', (user_id,)).fetchone())

    def active(self, user_id):
        """The account behind a credential, or None when it is unknown or disabled."""
        with self.database.connect() as db:
            return self.account(db.execute('SELECT * FROM users WHERE user_id=? AND disabled=0',
                                           (user_id,)).fetchone())

    def by_username(self, username):
        if not isinstance(username, str):
            return None
        with self.database.connect() as db:
            return self.account(db.execute('SELECT * FROM users WHERE username=?',
                                           (username.strip().lower(),)).fetchone())

    def ids(self):
        with self.database.connect() as db:
            return [r['user_id'] for r in db.execute('SELECT user_id FROM users WHERE disabled=0 ORDER BY created_at')]

    def count(self):
        with self.database.connect() as db:
            return db.execute('SELECT COUNT(*) FROM users').fetchone()[0]

    def authenticate(self, username, password):
        """A verified account, or DomainError. Unknown users cost the same work as wrong passwords."""
        if not isinstance(username, str) or not isinstance(password, str):
            raise DomainError('invalid_credentials', 401)
        name, now = username.strip().lower(), time.time()
        with self.database.connect() as db:
            db.execute('DELETE FROM login_failures WHERE at<?', (now - FAILURE_WINDOW,))
            failures = db.execute('SELECT COUNT(*) FROM login_failures WHERE username=? AND at>?',
                                  (name, now - FAILURE_WINDOW)).fetchone()[0]
            row = db.execute('SELECT * FROM users WHERE username=?', (name,)).fetchone()
        if failures >= MAX_FAILURES:
            raise DomainError('too_many_attempts', 429)
        stored = row['password_hash'] if row else hash_password('unknown-account', self.iterations)
        if not verify_password(stored, password) or (row and row['disabled']):
            with self.database.connect() as db:
                db.execute('INSERT INTO login_failures VALUES (?,?)', (name, time.time()))
            raise DomainError('invalid_credentials', 401)
        with self.database.connect() as db:
            db.execute('DELETE FROM login_failures WHERE username=?', (name,))
        return self.account(row)

    def set_password(self, user_id, current_password, new_password):
        """The user's own change: the current password is required."""
        validate_password(new_password)
        with self.database.connect() as db:
            row = db.execute('SELECT * FROM users WHERE user_id=?', (user_id,)).fetchone()
            if row is None:
                raise DomainError('user_not_found', 404)
            if not verify_password(row['password_hash'], current_password or ''):
                raise DomainError('invalid_credentials', 401)
            self._store_password(db, user_id, new_password)
        return self.read(user_id)

    def reset_password(self, user_id, new_password):
        """Operator reset for a forgotten password. Only reachable with filesystem access to
        this database, which already holds the verifiers, so no current password is asked for."""
        validate_password(new_password)
        with self.database.connect() as db:
            changed = self._store_password(db, user_id, new_password)
        if not changed:
            raise DomainError('user_not_found', 404)
        return self.read(user_id)

    def _store_password(self, db, user_id, password):
        return db.execute('UPDATE users SET password_hash=?,password_changed_at=? WHERE user_id=?',
                          (hash_password(password, self.iterations),
                           datetime.now(timezone.utc).isoformat(), user_id)).rowcount
