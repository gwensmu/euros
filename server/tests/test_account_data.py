"""Exporting and deleting an account: only that account's data, all of it, nothing else."""
import json

from conftest import PASSWORD
from euros_server.account_data import delete_account, export_account, row_counts
from euros_server.database import transaction
from euros_server.domain import Store
from euros_server.models import FoodEstimate, model_usage, sessions, oauth_tokens, oauth_grants
from euros_server.security import TokenBudget


def activity(identity, title):
    return {'command_id': f'cmd-{identity}', 'entity_type': 'activities', 'entity_id': identity,
            'expected_revision': 0, 'operation': 'upsert',
            'payload': {'title': title, 'kind': 'bjj', 'scheduled_at': '2026-11-20T18:00:00-06:00', 'status': 'pending'}}


def populated(service):
    """Two accounts with records, a cached food estimate, model spend and a connected app."""
    tokens = {}
    for name in ('athlete', 'rival'):
        service.signup(name)
        service.logout()
        _, tokens[name] = service.connect(name)
        api = service.api(tokens[name]['access_token'])
        assert api('POST', '/v1/commands', json=activity(f'{name}-1', f'{name} class')).status_code == 200
        user = service.accounts.users.by_username(name)
        with Store(service.accounts.path, user['user_id']).session(write=True) as db:
            db.add(FoodEstimate(user_id=user['user_id'], key=name, input=f'{name} oats', result='{"kcal":"1"}'))
        budget = TokenBudget(service.accounts.path)
        budget.settle(budget.reserve(user['user_id'], 'food_draft'), 400)
    return tokens


def counts(service, name):
    user_id = service.accounts.users.by_username(name)['user_id']
    with transaction(service.accounts.path) as db:
        return row_counts(db, user_id)


def delete_form(service, password=PASSWORD, confirm='athlete', csrf=None):
    return service.client.post('/account/delete', data={
        'csrf': service.csrf('/account/data') if csrf is None else csrf,
        'password': password, 'confirm': confirm}, follow_redirects=False)


def test_export_is_the_signed_in_accounts_data_only(service):
    populated(service)
    service.login('athlete')
    response = service.client.get('/account/export')
    assert response.status_code == 200
    assert response.headers['content-disposition'].startswith('attachment; filename="euros-athlete-')
    snapshot = response.json()
    assert snapshot['format'] == 'euros-account-export' and snapshot['account']['username'] == 'athlete'
    titles = [r['payload']['title'] for r in snapshot['tables']['records']]
    assert titles == ['athlete class']
    assert [r['input'] for r in snapshot['tables']['food_item_cache']] == ['athlete oats']
    assert snapshot['connected_apps'][0]['scope']
    text = response.text
    assert 'rival' not in text
    assert 'password_hash' not in text and 'pbkdf2' not in text and 'token_hash' not in text


def test_export_needs_a_session(service):
    response = service.client.get('/account/export', follow_redirects=False)
    assert response.status_code == 303 and response.headers['location'] == '/account/login'


def test_delete_removes_the_account_and_everything_it_owns(service):
    tokens = populated(service)
    rival_before = counts(service, 'rival')
    user_id = service.accounts.users.by_username('athlete')['user_id']
    service.login('athlete')
    answer = delete_form(service)
    assert answer.status_code == 200 and 'were deleted' in answer.text
    assert 'euros_session=""' in answer.headers['set-cookie'] or 'Max-Age=0' in answer.headers['set-cookie']

    assert service.accounts.users.by_username('athlete') is None
    with transaction(service.accounts.path) as db:
        assert all(v == 0 for v in row_counts(db, user_id).values())
        for table in (sessions, oauth_tokens, oauth_grants):
            assert db.execute(table.select().where(table.c.user_id == user_id)).first() is None
        # The spend ledger stays: deleting an account never refunds the deployment's budget.
        assert db.execute(model_usage.select().where(model_usage.c.user_id == user_id)).first() is not None
    # The other account is untouched and still works.
    assert counts(service, 'rival') == rival_before
    rival = service.api(tokens['rival']['access_token'])
    assert [r['entity_id'] for r in rival('GET', '/v1/records').json()['records']] == ['rival-1']
    # The deleted account's app token and browser are both dead.
    assert service.api(tokens['athlete']['access_token'])('GET', '/v1/records').status_code == 401
    assert service.client.get('/account/', follow_redirects=False).status_code == 303
    assert service.login('athlete').status_code == 401
    # The username is free again, as a new, empty account.
    service.client.cookies.clear()
    created, _ = service.signup('athlete')
    assert created.status_code == 303 and counts(service, 'athlete')['records'] == 0


def test_delete_needs_the_password_the_username_and_the_form_token(service):
    populated(service)
    service.login('athlete')
    assert delete_form(service, password='not the password').status_code == 401
    wrong_name = delete_form(service, confirm='rival')
    assert wrong_name.status_code == 422 and 'Type your username exactly' in wrong_name.text
    assert delete_form(service, csrf='forged').status_code == 403
    assert service.accounts.users.by_username('athlete') is not None
    assert counts(service, 'athlete')['records'] == 1


def test_delete_and_export_functions_refuse_unknown_accounts(service):
    import pytest
    with pytest.raises(LookupError):
        delete_account(service.accounts.path, 'usr-' + 'f' * 32)
    with pytest.raises(LookupError):
        export_account(service.accounts.path, 'usr-' + 'f' * 32)


def test_export_parses_stored_json(service):
    populated(service)
    user_id = service.accounts.users.by_username('athlete')['user_id']
    snapshot = export_account(service.accounts.path, user_id)
    assert snapshot['tables']['food_item_cache'][0]['result'] == {'kcal': '1'}
    assert isinstance(snapshot['tables']['changes'][0]['record'], dict)
    json.dumps(snapshot)
