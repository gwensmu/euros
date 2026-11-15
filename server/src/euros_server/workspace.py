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
from .security import LimitedProvider
from .tracker import TrackerStore


class Workspace:
    def __init__(self, store, models):
        self.store = store
        self.plans = PlanSettings(store)
        self.models = copy.copy(models)
        self.models.provider = LimitedProvider(models.provider, store)
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
    def __init__(self, accounts, models):
        self.accounts, self.models = accounts, models
        self.lock = threading.Lock()
        self._services = {}

    def get(self, user_id):
        if self.accounts.users.active(user_id) is None:
            raise DomainError('unauthorized', 401)
        with self.lock:
            if user_id not in self._services:
                self._services[user_id] = Workspace(Store(self.accounts.path, user_id), self.models)
            return self._services[user_id]


class FixedWorkspace:
    """Compatibility for an already enrolled personal deployment, using the same schema."""
    def __init__(self, user_id, store, models):
        self.user_id = user_id
        self.workspace = Workspace(store, models)

    def get(self, user_id):
        if user_id != self.user_id:
            raise DomainError('unauthorized', 401)
        return self.workspace
