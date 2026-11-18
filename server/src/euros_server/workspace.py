"""Account-bound services over one shared database.

Only authenticated principals select a workspace. Services never accept user ownership
from record payloads, URL parameters or model output.
"""
import copy
import threading

from .domain import DomainError, Store
from .food_cache import FoodCache
from .meal_week import MealWeeks
from .plan import PlanSettings
from .reminder_copy import ReminderCopyService
from .security import DEFAULT_ACCOUNT_MONTHLY_TOKENS, DEFAULT_MONTHLY_TOKENS, LimitedProvider, TokenBudget
from .tracker import TrackerStore


class Workspace:
    def __init__(self, store, models, budget=None):
        self.store = store
        self.plans = PlanSettings(store)
        self.models = copy.copy(models)
        self.models.provider = LimitedProvider(models.provider, store, budget=budget)
        self.trackers = TrackerStore(store)
        self.meal_weeks = MealWeeks(store, self.plans)
        self.food_cache = FoodCache(store, self.models)
        self.reminder_copy = ReminderCopyService(self.models, store)

    @property
    def catalog(self):
        return self.meal_weeks.catalog

    def plan(self):
        return self.plans.plan()


class Workspaces:
    def __init__(self, accounts, models, monthly_tokens=DEFAULT_MONTHLY_TOKENS,
                 account_monthly_tokens=DEFAULT_ACCOUNT_MONTHLY_TOKENS):
        self.accounts, self.models = accounts, models
        # One allowance for the whole deployment, shared by every account's workspace.
        self.budget = TokenBudget(accounts.path, monthly_tokens, account_monthly_tokens)
        self.lock = threading.Lock()
        self._services = {}

    def get(self, user_id):
        if self.accounts.users.active(user_id) is None:
            raise DomainError('unauthorized', 401)
        with self.lock:
            if user_id not in self._services:
                self._services[user_id] = Workspace(Store(self.accounts.path, user_id), self.models, self.budget)
            return self._services[user_id]

    def forget(self, user_id):
        """Drop a deleted account's cached services."""
        with self.lock:
            self._services.pop(user_id, None)


class FixedWorkspace:
    """Compatibility for an already enrolled personal deployment, using the same schema."""
    def __init__(self, user_id, store, models, monthly_tokens=DEFAULT_MONTHLY_TOKENS,
                 account_monthly_tokens=DEFAULT_ACCOUNT_MONTHLY_TOKENS):
        self.user_id = user_id
        self.budget = TokenBudget(store.path, monthly_tokens, account_monthly_tokens)
        self.workspace = Workspace(store, models, self.budget)

    def get(self, user_id):
        if user_id != self.user_id:
            raise DomainError('unauthorized', 401)
        return self.workspace
