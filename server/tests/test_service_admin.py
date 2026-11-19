"""Migration and administration of the multi-account service."""
import json
import uuid

import pytest
from starlette.testclient import TestClient

from conftest import PASSWORD, SilentProvider
from euros_server.accounts import AccountStore
from euros_server.app import _adopt, create_app, create_service
from euros_server.domain import Store
from euros_server.llm import ModelService

API = 'app-' + 'x' * 40
MCP = 'mcp-' + 'y' * 40
ACTIVITY = {'command_id': 'c1', 'entity_type': 'activities', 'entity_id': 'a1', 'expected_revision': 0,
            'operation': 'upsert', 'payload': {'title': 'Practice', 'kind': 'bjj',
                                               'scheduled_at': '2026-11-20T18:00:00-06:00', 'status': 'pending'}}


def build(tmp_path, **kwargs):
    accounts = kwargs.pop('accounts', None) or AccountStore(tmp_path / 'euros.db', iterations=1000)
    kwargs.setdefault('allow_signup', True)
    app = create_service(tmp_path, model_service=ModelService(SilentProvider()), accounts=accounts, **kwargs)
    return accounts, app


def test_static_tokens_authenticate_as_one_named_account(tmp_path):
    accounts = AccountStore(tmp_path / 'euros.db', iterations=1000)
    owner = accounts.users.create('owner', PASSWORD)
    other = accounts.users.create('other', PASSWORD)
    _, app = build(tmp_path, accounts=accounts, static_tokens={'api': API, 'mcp': MCP}, static_username='owner')
    with TestClient(app, base_url='http://localhost') as client:
        headers = {'Authorization': f'Bearer {API}'}
        assert client.post('/v1/commands', headers=headers, json=ACTIVITY).status_code == 200
        assert client.get('/v1/records', headers=headers).json()['records'][0]['entity_id'] == 'a1'
        # The static token is bound to its surface, as before.
        assert client.get('/v1/records', headers={'Authorization': f'Bearer {MCP}'}).status_code == 401
    assert not (tmp_path / 'users').exists()
    assert Store(accounts.path, owner['user_id']).records()['records']
    assert Store(accounts.path, other['user_id']).records()['records'] == []


def test_static_tokens_need_an_existing_account(tmp_path):
    accounts = AccountStore(tmp_path / 'euros.db', iterations=1000)
    with pytest.raises(ValueError, match='EUROS_STATIC_ACCOUNT'):
        create_service(tmp_path, model_service=ModelService(SilentProvider()), accounts=accounts,
                       static_tokens={'api': API, 'mcp': MCP}, static_username='nobody')


def test_adopting_an_existing_single_account_database_keeps_its_records(tmp_path):
    source = tmp_path / 'legacy' / 'euros.db'
    Store(source).command(ACTIVITY)
    accounts = AccountStore(tmp_path / 'euros.db', iterations=1000)
    accounts.users.create('owner', PASSWORD)
    adopted = _adopt(accounts, tmp_path, source, 'owner')
    assert adopted['username'] == 'owner' and 'copied, not deleted' in adopted['note']
    assert Store(source).records()['records'], 'the source database is left in place'
    _, app = build(tmp_path, accounts=accounts)
    with TestClient(app, base_url='http://localhost') as client:
        service_accounts = accounts
        client.get('/account/login')
        client.post('/account/login', data={'form_token': client.cookies.get('euros_form'),
                                            'username': 'owner', 'password': PASSWORD})
        assert 'Euros' in client.get('/account/').text
    store = Store(accounts.path, service_accounts.users.by_username('owner')['user_id'])
    assert [r['entity_id'] for r in store.records()['records']] == ['a1']
    with pytest.raises(ValueError, match='already has data'):
        _adopt(accounts, tmp_path, source, 'owner')


def test_adopting_refuses_a_missing_database(tmp_path):
    accounts = AccountStore(tmp_path / 'euros.db', iterations=1000)
    accounts.users.create('owner', PASSWORD)
    with pytest.raises(ValueError, match='No database at'):
        _adopt(accounts, tmp_path, tmp_path / 'nowhere.db', 'owner')


def test_the_single_account_service_still_runs_without_accounts(tmp_path):
    app = create_app(Store(tmp_path / 'euros.db'), API, MCP, model_service=ModelService(SilentProvider()))
    with TestClient(app, base_url='http://localhost') as client:
        headers = {'Authorization': f'Bearer {API}'}
        assert client.post('/v1/commands', headers=headers, json=ACTIVITY).status_code == 200
        assert client.get('/v1/plan', headers=headers).json()['source'] == 'default'
        # No account site and no OAuth endpoints exist in that mode.
        assert client.get('/account/login').status_code == 401
        assert client.get('/.well-known/oauth-authorization-server').status_code == 401


def test_retired_review_routes_are_absent_for_every_account(service):
    service.signup('athlete')
    service.logout()
    _, first = service.connect('athlete')
    athlete = service.api(first['access_token'])
    assert athlete('POST', '/v1/review/jobs', json={}).status_code == 404
    assert athlete('POST', '/v1/review/week', json={}).status_code == 404
    assert athlete('GET', '/v1/review/jobs/old').status_code == 404
    # Reminder jokes are chosen on the phone; the model-backed route is gone.
    assert athlete('POST', '/v1/reminders/copy', json={}).status_code == 404
