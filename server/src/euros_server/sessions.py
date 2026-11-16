"""Browser sessions for the account site.

A session is an opaque random value handed to one browser and stored only as its digest,
alongside the CSRF token its forms must carry. Cookie attributes and form checking are the
web layer's job (web.py); this store only decides whether a presented value still names a
live account.
"""
import time
from sqlalchemy import select, insert, delete
from .models import sessions

from .credentials import digest, new_secret

SESSION_TTL = 14 * 86400


class SessionStore:
    def __init__(self, database, users):
        self.database, self.users = database, users

    def start(self, user_id):
        """A new cookie value and its CSRF token. Signing in never reuses an existing session."""
        value, csrf, now = new_secret(), new_secret(), time.time()
        with self.database.transaction(write=True) as db:
            db.execute(delete(sessions).where(sessions.c.expires_at < now))
            db.execute(insert(sessions).values(session_hash=digest(value), user_id=user_id, csrf=csrf, created_at=now, expires_at=now+SESSION_TTL))
        return value, csrf

    def read(self, value):
        """The signed-in account and its CSRF token, or None for an expired, unknown or
        disabled-account cookie."""
        if not isinstance(value, str) or not value:
            return None
        with self.database.transaction(write=True) as db:
            row = db.execute(select(sessions).where(sessions.c.session_hash == digest(value), sessions.c.expires_at > time.time())).mappings().first()
        if row is None:
            return None
        user = self.users.active(row['user_id'])
        return None if user is None else dict(user=user, csrf=row['csrf'])

    def end(self, value):
        with self.database.transaction(write=True) as db:
            db.execute(delete(sessions).where(sessions.c.session_hash == digest(value or "")))

    def end_all(self, user_id, keep=None):
        """Sign an account's browsers out, optionally sparing the one asking."""
        with self.database.transaction(write=True) as db:
            db.execute(delete(sessions).where(sessions.c.user_id == user_id, sessions.c.session_hash != digest(keep or "")))
