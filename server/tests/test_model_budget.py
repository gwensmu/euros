"""The deployment-wide token budget shared by every account. Synthetic providers only."""
import asyncio
import json
from concurrent.futures import ThreadPoolExecutor

import httpx
import pytest

from conftest import PASSWORD
from euros_server.accounts import AccountStore
from euros_server.domain import DomainError, Store
from euros_server.llm import OpenAIProvider
from euros_server.models import model_usage
from euros_server.database import transaction
from euros_server.security import ESTIMATED_TOKENS, LimitedProvider, TokenBudget
from euros_server.workspace import Workspaces


class Reporting:
    """Reports provider usage the way OpenAIProvider does."""
    def __init__(self, tokens=321, fail=False):
        self.tokens, self.fail, self.calls = tokens, fail, 0

    async def generate(self, usage=None, **kwargs):
        self.calls += 1
        if self.tokens is not None and usage is not None:
            usage['total_tokens'] = self.tokens
        if self.fail:
            raise DomainError('model_refused', 422)
        return {'ok': True}


@pytest.fixture
def pair(tmp_path):
    accounts = AccountStore(tmp_path / 'euros.db', iterations=1000)
    a = accounts.users.create('athlete', PASSWORD)
    b = accounts.users.create('rival', PASSWORD)
    return accounts, Store(accounts.path, a['user_id']), Store(accounts.path, b['user_id'])


def ledger(path):
    with transaction(path) as db:
        return [dict(r) for r in db.execute(model_usage.select().order_by(model_usage.c.id)).mappings()]


def call(limiter, name='food_draft'):
    return asyncio.run(limiter.generate(name=name))


def test_reported_usage_replaces_the_reservation(pair):
    accounts, a, _ = pair
    budget = TokenBudget(accounts.path, 10_000)
    call(LimitedProvider(Reporting(321), a, budget=budget))
    [row] = ledger(accounts.path)
    assert (row['user_id'], row['kind'], row['tokens'], row['measured']) == (a.user_id, 'food_draft', 321, 1)
    assert budget.spent() == 321


def test_failures_keep_their_cost(pair):
    accounts, a, _ = pair
    budget = TokenBudget(accounts.path, 10_000)
    # A refusal after the provider reported usage was billed: record what it reported.
    with pytest.raises(DomainError, match='model_refused'):
        call(LimitedProvider(Reporting(77, fail=True), a, budget=budget))
    # No reported usage (timeout, network failure): the estimate stands.
    with pytest.raises(DomainError, match='model_refused'):
        call(LimitedProvider(Reporting(None, fail=True), a, budget=budget))
    assert [(r['tokens'], r['measured']) for r in ledger(accounts.path)] == [(77, 1), (ESTIMATED_TOKENS['food_draft'], 0)]


def test_one_budget_is_shared_across_accounts(pair):
    accounts, a, b = pair
    budget = TokenBudget(accounts.path, 2 * ESTIMATED_TOKENS['food_draft'])
    provider = Reporting(ESTIMATED_TOKENS['food_draft'])
    call(LimitedProvider(provider, a, budget=budget))
    call(LimitedProvider(provider, b, budget=budget))
    with pytest.raises(DomainError, match='model_budget_exhausted') as refused:
        call(LimitedProvider(provider, a, budget=budget))
    assert refused.value.status == 429 and provider.calls == 2


def test_a_budget_refusal_gives_back_the_accounts_hourly_slot(pair):
    accounts, a, _ = pair
    limiter = LimitedProvider(Reporting(), a, hourly_food=1, budget=TokenBudget(accounts.path, 0))
    with pytest.raises(DomainError, match='model_budget_exhausted'):
        limiter.reserve('food_draft')
    # Raising the budget lets the same hour's single slot through.
    limiter.budget = TokenBudget(accounts.path, 10_000)
    limiter.reserve('food_draft')


def test_the_month_rolls_over_in_utc(pair):
    accounts, a, _ = pair
    now = [1_793_491_199.0]  # 2026-10-31T23:59:59Z
    budget = TokenBudget(accounts.path, ESTIMATED_TOKENS['food_draft'], clock=lambda: now[0])
    budget.reserve(a.user_id, 'food_draft')
    with pytest.raises(DomainError, match='model_budget_exhausted'):
        budget.reserve(a.user_id, 'food_draft')
    now[0] += 1
    budget.reserve(a.user_id, 'food_draft')
    assert [r['month'] for r in ledger(accounts.path)] == ['2026-10', '2026-11']


def test_concurrent_accounts_cannot_both_take_the_last_tokens(pair):
    accounts, a, b = pair
    budget = TokenBudget(accounts.path, ESTIMATED_TOKENS['food_draft'])
    def take(store):
        try:
            budget.reserve(store.user_id, 'food_draft')
            return True
        except DomainError:
            return False
    with ThreadPoolExecutor(max_workers=4) as pool:
        assert sum(pool.map(take, [a, b, a, b])) == 1


def test_workspaces_share_one_budget(pair):
    from conftest import SilentProvider
    from euros_server.llm import ModelService
    accounts, a, b = pair
    spaces = Workspaces(accounts, ModelService(SilentProvider()), monthly_tokens=1234)
    first, second = spaces.get(a.user_id), spaces.get(b.user_id)
    assert first.models.provider.budget is second.models.provider.budget is spaces.budget
    assert spaces.budget.monthly_tokens == 1234


def test_openai_provider_reports_total_tokens():
    def respond(request):
        return httpx.Response(200, json={'status': 'completed', 'usage': {'input_tokens': 400, 'output_tokens': 90, 'total_tokens': 490},
                                         'output': [{'type': 'message', 'content': [{'type': 'output_text', 'text': json.dumps({'joke_id': 'none'})}]}]})
    usage = {}
    provider = OpenAIProvider('test-secret-not-real', httpx.MockTransport(respond))
    result = asyncio.run(provider.generate(model='m', instructions='i', data={}, schema={}, name='food_draft',
                                           reasoning='low', usage=usage))
    assert result == {'joke_id': 'none'} and usage == {'total_tokens': 490}


def test_one_account_cannot_spend_more_than_its_monthly_share(pair):
    accounts, a, b = pair
    food = ESTIMATED_TOKENS['food_draft']
    budget = TokenBudget(accounts.path, monthly_tokens=10 * food, account_monthly_tokens=2 * food)
    provider = Reporting(food)
    call(LimitedProvider(provider, a, budget=budget))
    call(LimitedProvider(provider, a, budget=budget))
    with pytest.raises(DomainError, match='model_account_budget_exhausted') as refused:
        call(LimitedProvider(provider, a, budget=budget))
    assert refused.value.status == 429 and provider.calls == 2
    # Another account still has its own share of the deployment budget.
    call(LimitedProvider(provider, b, budget=budget))
    assert budget.spent(user_id=a.user_id) == 2 * food and budget.spent(user_id=b.user_id) == food


def test_the_deployment_budget_still_binds_when_account_shares_add_up_to_more(pair):
    accounts, a, b = pair
    food = ESTIMATED_TOKENS['food_draft']
    budget = TokenBudget(accounts.path, monthly_tokens=3 * food, account_monthly_tokens=2 * food)
    for store in (a, a, b):
        budget.reserve(store.user_id, 'food_draft')
    with pytest.raises(DomainError, match='model_budget_exhausted'):
        budget.reserve(b.user_id, 'food_draft')


def test_concurrent_calls_from_one_account_cannot_overrun_its_share(pair):
    accounts, a, _ = pair
    budget = TokenBudget(accounts.path, 100 * ESTIMATED_TOKENS['food_draft'],
                         account_monthly_tokens=2 * ESTIMATED_TOKENS['food_draft'])
    def take(_):
        try:
            budget.reserve(a.user_id, 'food_draft')
            return True
        except DomainError:
            return False
    with ThreadPoolExecutor(max_workers=6) as pool:
        assert sum(pool.map(take, range(6))) == 2


def test_an_account_cap_refusal_gives_back_the_hourly_slot(pair):
    accounts, a, _ = pair
    limiter = LimitedProvider(Reporting(), a, hourly_food=1,
                              budget=TokenBudget(accounts.path, 10_000, account_monthly_tokens=0))
    with pytest.raises(DomainError, match='model_account_budget_exhausted'):
        limiter.reserve('food_draft')
    limiter.budget = TokenBudget(accounts.path, 10_000, account_monthly_tokens=10_000)
    limiter.reserve('food_draft')


def test_workspaces_pass_the_account_cap_through(pair):
    from conftest import SilentProvider
    from euros_server.llm import ModelService
    accounts, a, _ = pair
    spaces = Workspaces(accounts, ModelService(SilentProvider()), monthly_tokens=9000, account_monthly_tokens=4000)
    assert spaces.get(a.user_id).models.provider.budget.account_monthly_tokens == 4000
