"""Who may sign in: account identities, their passwords and sign-in throttling.

A user row is the only place an account exists. Training records share the same database,
with account-scoped domain access (workspace.py); this store only manages identities.
"""
import re
from sqlalchemy import select, insert, update, delete, func
from sqlalchemy.exc import IntegrityError
from .models import User, login_failures
users = User.__table__
import time
import uuid
from datetime import datetime, timezone

from .credentials import ITERATIONS, hash_password, validate_password, verify_password
from .domain import DomainError

USERNAME = re.compile(r'[a-z0-9][a-z0-9._-]{2,31}')
EMAIL_USERNAME = re.compile(r"[a-z0-9][a-z0-9._+\-]{0,63}@[a-z0-9](?:[a-z0-9\-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9\-]*[a-z0-9])?)+")
# Failed sign-ins per username within the window before the account stops answering attempts.
MAX_FAILURES, FAILURE_WINDOW = 10, 900


def validate_username(value):
    if not isinstance(value, str) or len(value.strip()) > 254 or not (USERNAME.fullmatch(value.strip().lower()) or EMAIL_USERNAME.fullmatch(value.strip().lower())):
        raise DomainError('invalid_username', 422,
                          rule='A 3-32 character username or an email address')
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
        # Verified against when the username is unknown, so that check costs exactly one
        # derivation like a wrong password does. Hashing a fresh one per attempt would cost two
        # and tell an attacker by timing which usernames exist.
        self._unknown = hash_password('unknown-account', iterations)

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
            with self.database.transaction(write=True) as db:
                db.execute(insert(users).values(user_id=user_id, username=username, display_name=display, password_hash=hash_password(password, self.iterations), created_at=stamp, password_changed_at=stamp, disabled=0))
        except IntegrityError:
            raise DomainError('username_taken', 409) from None
        return self.read(user_id)

    def read(self, user_id):
        with self.database.transaction() as db:
            return self.account(db.execute(select(users).where(users.c.user_id == user_id)).mappings().first())

    def active(self, user_id):
        """The account behind a credential, or None when it is unknown or disabled."""
        with self.database.transaction() as db:
            return self.account(db.execute(select(users).where(users.c.user_id == user_id, users.c.disabled == 0)).mappings().first())

    def by_username(self, username):
        if not isinstance(username, str):
            return None
        with self.database.transaction() as db:
            return self.account(db.execute(select(users).where(users.c.username == username.strip().lower())).mappings().first())

    def ids(self):
        with self.database.transaction() as db:
            return list(db.scalars(select(users.c.user_id).where(users.c.disabled == 0).order_by(users.c.created_at)))

    def count(self):
        with self.database.transaction() as db:
            return db.scalar(select(func.count()).select_from(users))

    def authenticate(self, username, password):
        """A verified account, or DomainError. Unknown users cost the same work as wrong passwords."""
        if not isinstance(username, str) or not isinstance(password, str):
            raise DomainError('invalid_credentials', 401)
        name, now = username.strip().lower(), time.time()
        with self.database.transaction(write=True) as db:
            db.execute(delete(login_failures).where(login_failures.c.at < now - FAILURE_WINDOW))
            failures = db.scalar(select(func.count()).select_from(login_failures).where(login_failures.c.username == name, login_failures.c.at > now - FAILURE_WINDOW))
            row = db.execute(select(users).where(users.c.username == name)).mappings().first()
        if failures >= MAX_FAILURES:
            raise DomainError('too_many_attempts', 429)
        stored = row['password_hash'] if row else self._unknown
        if not verify_password(stored, password) or (row and row['disabled']):
            with self.database.transaction(write=True) as db:
                db.execute(insert(login_failures).values(username=name, at=time.time()))
            raise DomainError('invalid_credentials', 401)
        with self.database.transaction(write=True) as db:
            db.execute(delete(login_failures).where(login_failures.c.username == name))
        return self.account(row)

    def set_password(self, user_id, current_password, new_password):
        """The user's own change: the current password is required."""
        validate_password(new_password)
        with self.database.transaction(write=True) as db:
            row = db.execute(select(users).where(users.c.user_id == user_id)).mappings().first()
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
        with self.database.transaction(write=True) as db:
            changed = self._store_password(db, user_id, new_password)
        if not changed:
            raise DomainError('user_not_found', 404)
        return self.read(user_id)

    def _store_password(self, db, user_id, password):
        return db.execute(update(users).where(users.c.user_id == user_id).values(password_hash=hash_password(password, self.iterations), password_changed_at=datetime.now(timezone.utc).isoformat())).rowcount

    def all_ids(self):
        """Every account, including disabled ones, for the operator view."""
        with self.database.transaction() as db:
            return list(db.scalars(select(users.c.user_id).where(users.c.password_hash != '!').order_by(users.c.created_at)))

    def set_disabled(self, user_id, disabled):
        """Operator switch. A disabled account cannot sign in, and its sessions and tokens stop
        working at once (sessions.read and oauth.access both check); grants are kept so that
        enabling the account again restores its connected apps."""
        with self.database.transaction(write=True) as db:
            changed = db.execute(update(users).where(users.c.user_id == user_id).values(disabled=1 if disabled else 0)).rowcount
        if not changed:
            raise DomainError('user_not_found', 404)
        return self.read(user_id)
