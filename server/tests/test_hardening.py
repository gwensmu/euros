"""Multi-user deployment hardening: sign-in cost, shared model capacity and health checks."""
import asyncio
import threading

import pytest

from conftest import PASSWORD
from euros_server import credentials
from euros_server.accounts import AccountStore
from euros_server.domain import DomainError, Store
from euros_server.security import ConcurrencyGate, LimitedProvider


def count_derivations(monkeypatch):
    calls = []
    real = credentials.hashlib.pbkdf2_hmac

    def counting(*args, **kwargs):
        calls.append(args)
        return real(*args, **kwargs)
    monkeypatch.setattr(credentials.hashlib, 'pbkdf2_hmac', counting)
    return calls


def test_an_unknown_username_costs_the_same_as_a_wrong_password(tmp_path, monkeypatch):
    accounts = AccountStore(tmp_path / 'euros.db', iterations=1000)
    accounts.users.create('athlete', PASSWORD)
    calls = count_derivations(monkeypatch)
    for username in ('athlete', 'nobody-here'):
        calls.clear()
        with pytest.raises(DomainError) as refused:
            accounts.users.authenticate(username, 'wrong password!')
        assert refused.value.body['error'] == 'invalid_credentials'
        assert len(calls) == 1, username


def test_sign_in_derives_passwords_off_the_event_loop(service, monkeypatch):
    seen = []
    real = service.accounts.users.authenticate

    def recording(*args):
        seen.append(threading.current_thread().name)
        return real(*args)
    monkeypatch.setattr(service.accounts.users, 'authenticate', recording)
    service.signup('athlete')
    service.logout()
    assert service.login('athlete').status_code == 303
    client = service.register_client()
    service.logout()
    assert service.code_from(service.authorize(client['client_id'], 'athlete'))
    assert len(seen) == 2
    loop_thread = service.client.portal.call(lambda: threading.current_thread().name)
    assert loop_thread not in seen


class Waiting:
    def __init__(self):
        self.started, self.release = asyncio.Event(), asyncio.Event()

    async def generate(self, usage=None, **kwargs):
        self.started.set()
        await self.release.wait()
        return {'ok': True}


def test_model_calls_in_flight_are_bounded_across_accounts(tmp_path):
    accounts = AccountStore(tmp_path / 'euros.db', iterations=1000)
    a, b = (Store(accounts.path, accounts.users.create(name, PASSWORD)['user_id']) for name in ('athlete', 'rival'))
    gate = ConcurrencyGate(1)

    async def scenario():
        provider = Waiting()
        first = LimitedProvider(provider, a, shared=gate)
        second = LimitedProvider(provider, b, shared=gate)
        running = asyncio.create_task(first.generate(name='food_draft'))
        await provider.started.wait()
        with pytest.raises(DomainError) as busy:
            await second.generate(name='food_draft')
        assert busy.value.body['error'] == 'model_busy'
        # The refused call held no per-account capacity of its own.
        assert second.active == 0
        provider.release.set()
        assert await running == {'ok': True}
        assert gate.active == 0
        assert await second.generate(name='food_draft') == {'ok': True}
    asyncio.run(scenario())


def test_workspaces_share_one_model_gate(tmp_path):
    from conftest import SilentProvider
    from euros_server.llm import ModelService
    from euros_server.workspace import Workspaces
    accounts = AccountStore(tmp_path / 'euros.db', iterations=1000)
    spaces = Workspaces(accounts, ModelService(SilentProvider()))
    first, second = (spaces.get(accounts.users.create(n, PASSWORD)['user_id']) for n in ('athlete', 'rival'))
    assert first.models.provider.shared is second.models.provider.shared is spaces.model_calls


def test_health_answers_without_credentials(service):
    response = service.client.get('/healthz')
    assert response.status_code == 200
    assert response.json() == {'status': 'ok'}
    assert response.headers['cache-control'] == 'no-store'


def test_health_reports_an_unreachable_database_without_detail(service, monkeypatch):
    import euros_server.database as database

    def broken(*args, **kwargs):
        raise RuntimeError('connection refused to db.internal:5432')
    monkeypatch.setattr(database, 'transaction', broken)
    response = service.client.get('/healthz')
    assert response.status_code == 503
    assert response.json() == {'status': 'unavailable'}
