"""Shared transport bounds and persistent inference allowances per account."""
from sqlalchemy import select, func, delete, insert, update, text
from .database import transaction
from .models import InferenceRequest, model_usage
import threading
import time
from datetime import datetime, timezone
from starlette.responses import JSONResponse
from .domain import DomainError
from .localtime import ZONE


class BodyLimit:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http':
            return await self.app(scope, receive, send)
        limit = {'/v1/tracker/import': 4194304,
                 '/v1/interpret/food': 40000, '/v1/reminders/copy': 16384}.get(scope['path'], 1048576 if scope['path'].startswith('/mcp') else 262144)
        body = bytearray()
        while True:
            message = await receive()
            if message['type'] == 'http.disconnect':
                return
            chunk = message.get('body', b'')
            if len(body) + len(chunk) > limit:
                return await JSONResponse({'error': 'request_too_large'}, 413)(scope, receive, send)
            body.extend(chunk)
            if not message.get('more_body', False):
                break
        delivered = False
        async def bounded_receive():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {'type': 'http.request', 'body': bytes(body), 'more_body': False}
            return await receive()
        await self.app(scope, bounded_receive, send)


# Tokens held for one call before the provider reports its real usage. A food draft sends
# about 1.35 KB of instructions and schema plus the item (~400 input tokens) and returns ~80
# tokens of JSON plus low-effort reasoning. Reservations
# are deliberately above typical use so concurrent calls cannot overrun the cap by much.
ESTIMATED_TOKENS = {'food_draft': 1500, 'reminder_copy': 1500}
# Operator-configurable deployment and per-account monthly token allowances.
DEFAULT_MONTHLY_TOKENS = 5_000_000
DEFAULT_ACCOUNT_MONTHLY_TOKENS = 1_000_000


def usage_month(timestamp):
    return datetime.fromtimestamp(timestamp, timezone.utc).strftime('%Y-%m')


class TokenBudget:
    """One monthly token allowance shared by every account on the deployment, plus a monthly
    cap on any one account's share of it.

    A call reserves its estimate before spending; the provider's reported usage then replaces
    the estimate. A failed or cancelled call keeps its reservation, because the provider may
    have billed it. Months are UTC calendar months, like provider billing.
    """
    def __init__(self, path, monthly_tokens=DEFAULT_MONTHLY_TOKENS,
                 account_monthly_tokens=DEFAULT_ACCOUNT_MONTHLY_TOKENS, clock=time.time):
        self.path, self.clock = str(path), clock
        self.monthly_tokens, self.account_monthly_tokens = monthly_tokens, account_monthly_tokens

    def _lock(self, db):
        # SQLite write transactions are already serialized (BEGIN IMMEDIATE); PostgreSQL
        # needs one deployment-wide lock so two accounts cannot both take the last tokens.
        if db.dialect.name == 'postgresql':
            db.execute(text('SELECT pg_advisory_xact_lock(4207150611)'))

    def spent(self, month=None, user_id=None):
        month = month or usage_month(self.clock())
        with transaction(self.path) as db:
            query = select(func.coalesce(func.sum(model_usage.c.tokens), 0)).where(model_usage.c.month == month)
            if user_id is not None:
                query = query.where(model_usage.c.user_id == user_id)
            return int(db.scalar(query))

    def reserve(self, user_id, kind):
        """A reservation id, or DomainError when the deployment's or this account's monthly
        allowance cannot cover the call. Both are checked under one lock."""
        now = self.clock()
        month, estimate = usage_month(now), ESTIMATED_TOKENS.get(kind, max(ESTIMATED_TOKENS.values()))
        with transaction(self.path, write=True) as db:
            self._lock(db)
            total = func.coalesce(func.sum(model_usage.c.tokens), 0)
            spent = db.scalar(select(total).where(model_usage.c.month == month))
            if self.monthly_tokens is not None and spent + estimate > self.monthly_tokens:
                raise DomainError('model_budget_exhausted', 429)
            mine = db.scalar(select(total).where(model_usage.c.month == month, model_usage.c.user_id == user_id))
            if self.account_monthly_tokens is not None and mine + estimate > self.account_monthly_tokens:
                raise DomainError('model_account_budget_exhausted', 429)
            return db.execute(insert(model_usage).values(user_id=user_id, at=now, month=month, kind=kind,
                                                         tokens=estimate, measured=0)).inserted_primary_key[0]

    def settle(self, reservation, tokens):
        """Replace a reservation's estimate with the tokens the provider reported."""
        if not isinstance(tokens, int) or isinstance(tokens, bool) or tokens < 0:
            return
        with transaction(self.path, write=True) as db:
            db.execute(update(model_usage).where(model_usage.c.id == reservation).values(tokens=tokens, measured=1))


class LimitedProvider:
    """Reserve before spending; failures count because provider billing may have occurred.

    Per account: food drafts per rolling hour and any call per rolling minute, all surviving restarts. Across accounts: the deployment's TokenBudget.
    Concurrency is process local: deployment runs one Uvicorn worker. No user text or model
    outputs are stored.
    """
    def __init__(self, provider, store, hourly_food=120, per_minute=60, concurrent=2, budget=None,
                 clock=time.time):
        self.provider, self.store, self.clock, self.budget = provider, store, clock, budget
        self.windows = {'food_draft': (hourly_food, 3600)}
        self.per_minute, self.concurrent = per_minute, concurrent
        self.active = 0
        self.lock = threading.Lock()

    def reserve(self, kind):
        now = self.clock()
        day = datetime.fromtimestamp(now, ZONE).date().isoformat()
        with self.store.session(write=True) as db:
            if kind != 'reminder_copy' and kind not in self.windows:
                raise DomainError('model_not_configured', 503)
            if kind == 'reminder_copy':
                if db.scalar(select(func.count(InferenceRequest.id)).where(InferenceRequest.day == day, InferenceRequest.kind == kind)) >= 12:
                    raise DomainError('model_daily_limit', 429)
            else:
                limit, seconds = self.windows[kind]
                if db.scalar(select(func.count(InferenceRequest.id)).where(InferenceRequest.at > now - seconds, InferenceRequest.kind == kind)) >= limit:
                    raise DomainError('model_hourly_limit', 429)
            if db.scalar(select(func.count(InferenceRequest.id)).where(InferenceRequest.at > now - 60)) >= self.per_minute:
                raise DomainError('model_rate_limit', 429)
            db.execute(delete(InferenceRequest).where(InferenceRequest.day < day, InferenceRequest.at <= now - 86400 * 7))
            slot = InferenceRequest(user_id=db.user_id, at=now, day=day, kind=kind)
            db.add(slot)
        if self.budget is None:
            return None
        # Per-account allowance first, so a refused request never touches the shared budget;
        # a call the shared budget refuses gives the account its slot back.
        try:
            return self.budget.reserve(self.store.user_id, kind)
        except DomainError:
            with self.store.session(write=True) as db:
                db.execute(delete(InferenceRequest).where(InferenceRequest.id == slot.id))
            raise

    async def generate(self, **kwargs):
        with self.lock:
            if self.active >= self.concurrent:
                raise DomainError('model_busy', 429)
            self.active += 1
        try:
            reservation = self.reserve(kwargs['name'])
            usage = {}
            try:
                return await self.provider.generate(**kwargs, usage=usage)
            finally:
                # Reported usage is billed usage, including for a refused or invalid answer.
                if reservation is not None and 'total_tokens' in usage:
                    self.budget.settle(reservation, usage['total_tokens'])
        finally:
            with self.lock:
                self.active -= 1
