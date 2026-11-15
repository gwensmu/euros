"""Browser sessions for the account site.

A session is an opaque random value handed to one browser and stored only as its digest,
alongside the CSRF token its forms must carry. Cookie attributes and form checking are the
web layer's job (web.py); this store only decides whether a presented value still names a
live account.
"""
import time

from .credentials import digest, new_secret

SESSION_TTL = 14 * 86400


class SessionStore:
    def __init__(self, database, users):
        self.database, self.users = database, users
        with database.connect() as db:
            db.execute('''CREATE TABLE IF NOT EXISTS sessions (
                            session_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL, csrf TEXT NOT NULL,
                            created_at REAL NOT NULL, expires_at REAL NOT NULL)''')

    def start(self, user_id):
        """A new cookie value and its CSRF token. Signing in never reuses an existing session."""
        value, csrf, now = new_secret(), new_secret(), time.time()
        with self.database.connect() as db:
            db.execute('DELETE FROM sessions WHERE expires_at<?', (now,))
            db.execute('INSERT INTO sessions VALUES (?,?,?,?,?)',
                       (digest(value), user_id, csrf, now, now + SESSION_TTL))
        return value, csrf

    def read(self, value):
        """The signed-in account and its CSRF token, or None for an expired, unknown or
        disabled-account cookie."""
        if not isinstance(value, str) or not value:
            return None
        with self.database.connect() as db:
            row = db.execute('SELECT * FROM sessions WHERE session_hash=? AND expires_at>?',
                             (digest(value), time.time())).fetchone()
        if row is None:
            return None
        user = self.users.active(row['user_id'])
        return None if user is None else dict(user=user, csrf=row['csrf'])

    def end(self, value):
        with self.database.connect() as db:
            db.execute('DELETE FROM sessions WHERE session_hash=?', (digest(value or ''),))

    def end_all(self, user_id, keep=None):
        """Sign an account's browsers out, optionally sparing the one asking."""
        with self.database.connect() as db:
            db.execute('DELETE FROM sessions WHERE user_id=? AND session_hash<>?',
                       (user_id, digest(keep or '')))
