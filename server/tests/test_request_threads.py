"""Handlers run their database work on request threads, never on the shared event loop."""
import asyncio
import threading

import anyio.to_thread
import httpx
import pytest
from starlette.testclient import TestClient

from conftest import PASSWORD, SilentProvider
from euros_server import credentials, database
from euros_server.app import create_app, request_threads
from euros_server.domain import DomainError, Store
from euros_server.llm import ModelService
from euros_server.web import local_path

API, MCP = 'a' * 40, 'm' * 40
MCP_HEADERS = {'Authorization': f'Bearer {MCP}', 'Accept': 'application/json, text/event-stream'}


def app_for(tmp_path):
    store = Store(tmp_path / 'db')
    return store, create_app(store, API, MCP, model_service=ModelService(SilentProvider()))


def test_a_slow_request_does_not_hold_up_other_requests(tmp_path, monkeypatch):
    store, app = app_for(tmp_path)
    entered, release = threading.Event(), threading.Event()
    real = Store.records

    def slow_records(self, entity_type=None):
        entered.set()
        release.wait(5)
        return real(self, entity_type)
    monkeypatch.setattr(Store, 'records', slow_records)

    async def scenario():
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url='http://localhost') as client:
            slow = asyncio.create_task(client.get('/v1/records', headers={'Authorization': f'Bearer {API}'}))
            await asyncio.to_thread(entered.wait, 5)
            # The slow handler is parked on its thread; the loop still answers everyone else.
            quick = await asyncio.wait_for(client.get('/healthz'), 2)
            assert quick.status_code == 200 and not slow.done()
            release.set()
            assert (await slow).status_code == 200
    asyncio.run(scenario())


def test_api_mcp_and_account_handlers_run_off_the_event_loop(service, monkeypatch):
    service.signup('athlete')
    _, tokens = service.connect('athlete')
    loop_thread = service.client.portal.call(lambda: threading.current_thread().name)
    seen = {}

    def recording(name, real):
        def wrapper(*args, **kwargs):
            seen[name] = threading.current_thread().name
            return real(*args, **kwargs)
        return wrapper
    monkeypatch.setattr(Store, 'records', recording('api', Store.records))
    monkeypatch.setattr(Store, 'command', recording('command', Store.command))
    oauth = service.accounts.oauth
    monkeypatch.setattr(oauth, 'access', recording('auth', oauth.access))
    api = service.api(tokens['access_token'])
    assert api('GET', '/v1/records').status_code == 200
    assert api('POST', '/v1/commands', json={}).status_code in (400, 422)
    service.login('athlete')
    sessions = service.accounts.sessions
    monkeypatch.setattr(sessions, 'read', recording('site', sessions.read))
    assert service.client.get('/account/').status_code == 200
    assert set(seen) == {'api', 'command', 'auth', 'site'}
    assert loop_thread not in seen.values(), seen


def test_mcp_tools_run_off_the_event_loop_and_keep_their_caller(tmp_path, monkeypatch):
    _, app = app_for(tmp_path)
    seen = []
    real = Store.records

    def recording(self, entity_type=None):
        seen.append(threading.current_thread().name)
        return real(self, entity_type)
    monkeypatch.setattr(Store, 'records', recording)
    with TestClient(app, base_url='http://localhost') as client:
        loop_thread = client.portal.call(lambda: threading.current_thread().name)
        reply = client.post('/mcp', headers=MCP_HEADERS, json={
            'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call',
            'params': {'name': 'read_records', 'arguments': {}}})
        assert reply.status_code == 200, reply.text
        assert reply.json()['result']['isError'] is False
    assert seen and loop_thread not in seen


def test_request_threads_match_the_connections_they_may_hold(tmp_path, monkeypatch):
    monkeypatch.setenv('EUROS_DB_POOL_SIZE', '6')
    monkeypatch.setenv('EUROS_DB_MAX_OVERFLOW', '3')
    _, app = app_for(tmp_path)
    with TestClient(app, base_url='http://localhost') as client:
        tokens = client.portal.call(lambda: anyio.to_thread.current_default_thread_limiter().total_tokens)
    # Nine connections, two kept for password derivation's own threads.
    assert tokens == 9 - credentials.CONCURRENT_DERIVATIONS


@pytest.mark.parametrize('name,value', [('EUROS_DB_POOL_SIZE', '0'), ('EUROS_DB_POOL_SIZE', 'many'),
                                        ('EUROS_DB_MAX_OVERFLOW', '-1')])
def test_pool_settings_refuse_unusable_values(monkeypatch, name, value):
    monkeypatch.setenv(name, value)
    with pytest.raises(ValueError, match=name):
        database.pool_settings()


def test_pool_defaults(monkeypatch):
    monkeypatch.delenv('EUROS_DB_POOL_SIZE', raising=False)
    monkeypatch.delenv('EUROS_DB_MAX_OVERFLOW', raising=False)
    assert database.pool_settings() == (8, 4)


def test_password_work_refuses_rather_than_queueing_without_bound(monkeypatch):
    monkeypatch.setattr(credentials, '_pending',
                        credentials.CONCURRENT_DERIVATIONS + credentials.QUEUED_DERIVATIONS)
    with pytest.raises(DomainError) as refused:
        asyncio.run(credentials.password_work(lambda: None))
    assert refused.value.body['error'] == 'too_many_attempts'


def test_password_work_releases_its_slot_on_failure(monkeypatch):
    monkeypatch.setattr(credentials, '_pending', 0)

    def fails():
        raise DomainError('invalid_credentials', 401)
    with pytest.raises(DomainError):
        asyncio.run(credentials.password_work(fails))
    assert credentials._pending == 0
    assert asyncio.run(credentials.password_work(lambda: threading.current_thread().name)).startswith('euros-password')


@pytest.mark.parametrize('destination', ['//evil.example/', '/\\evil.example', '/ok\\..\\x', '/a\nb', 'https://x'])
def test_logout_never_redirects_off_site(service, destination):
    service.signup('athlete')
    response = service.client.post('/account/logout', data={'csrf': service.csrf(), 'next': destination},
                                   follow_redirects=False)
    assert local_path(destination) is False
    assert response.headers['location'] == '/account/login'


def test_logout_keeps_a_local_destination(service):
    service.signup('athlete')
    response = service.client.post('/account/logout', data={'csrf': service.csrf(),
                                                            'next': '/oauth/authorize?client_id=x'},
                                   follow_redirects=False)
    assert response.headers['location'] == '/oauth/authorize?client_id=x'
