"""Authentication stores in the same SQLite database as account-owned training data."""

from .database import initialize, connect, transaction
from .credentials import ITERATIONS
from .oauth_store import OAuthStore
from .sessions import SessionStore
from .users import UserStore


class AccountStore:
    def __init__(self, path, iterations=ITERATIONS):
        self.path = str(path)
        initialize(self.path, legacy_owner=None)
        # Each store owns its own tables; this order is also the dependency order.
        self.users = UserStore(self, iterations)
        self.sessions = SessionStore(self, self.users)
        self.oauth = OAuthStore(self, self.users)

    def transaction(self, write=False):
        return transaction(self.path, write=write)

    def connect(self):
        return connect(self.path)
