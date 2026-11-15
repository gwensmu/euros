"""Two accounts on one service never see each other's training data."""
import json
from pathlib import Path

from conftest import PASSWORD


def activity(identity, title, when='2026-11-20T18:00:00-06:00'):
    return {'command_id': f'cmd-{identity}', 'entity_type': 'activities', 'entity_id': identity,
            'expected_revision': 0, 'operation': 'upsert',
            'payload': {'title': title, 'kind': 'bjj', 'scheduled_at': when, 'status': 'pending'}}


def weight(identity, local_date, pounds):
    return {'command_id': f'cmd-{identity}', 'entity_type': 'weight_entries', 'entity_id': identity,
            'expected_revision': 0, 'operation': 'upsert',
            'payload': {'local_date': local_date, 'measured_at': f'{local_date}T07:00:00-06:00',
                        'weight_lb': pounds}}


def two_accounts(service):
    service.signup('athlete')
    service.logout()
    service.signup('rival')
    service.logout()
    _, first = service.connect('athlete')
    _, second = service.connect('rival')
    return service.api(first['access_token']), service.api(second['access_token'])


def test_records_stay_inside_the_account_that_wrote_them(service):
    athlete, rival = two_accounts(service)
    assert athlete('POST', '/v1/commands', json=activity('a1', 'Athlete class')).status_code == 200
    assert rival('POST', '/v1/commands', json=activity('r1', 'Rival class')).status_code == 200
    assert [r['entity_id'] for r in athlete('GET', '/v1/records').json()['records']] == ['a1']
    assert [r['entity_id'] for r in rival('GET', '/v1/records').json()['records']] == ['r1']
    assert [c['entity_id'] for c in athlete('GET', '/v1/changes').json()['changes']] == ['a1']
    # The same entity id in both accounts is two separate records, not a conflict.
    assert rival('POST', '/v1/commands', json=activity('a1', 'Rival class two')).status_code == 200
    assert athlete('GET', '/v1/records').json()['records'][0]['payload']['title'] == 'Athlete class'


def test_one_account_cannot_reuse_another_accounts_command_id(service):
    athlete, rival = two_accounts(service)
    athlete('POST', '/v1/commands', json=activity('a1', 'Athlete class'))
    # Same command_id, different account: accepted on its own records, never answered from a
    # stranger's idempotency entry.
    answer = rival('POST', '/v1/commands', json=activity('a1', 'Rival class'))
    assert answer.status_code == 200 and answer.json()['record']['payload']['title'] == 'Rival class'


def test_nutrition_and_plans_are_per_account(service):
    athlete, rival = two_accounts(service)
    athlete('POST', '/v1/commands', json=weight('w1', '2026-11-10', 150))
    assert athlete('GET', '/v1/nutrition?local_date=2026-11-10').json()['entry_count'] == 0
    assert rival('GET', '/v1/records?entity_type=weight_entries').json()['records'] == []
    plan = athlete('GET', '/v1/plan').json()
    updated = {**plan['plan'], 'competition': {'name': 'Worlds', 'date': '2027-06-01'}}
    assert athlete('PUT', '/v1/plan', json={'plan': updated, 'expected_revision': 0}).status_code == 200
    assert athlete('GET', '/v1/plan').json()['plan']['competition']['name'] == 'Worlds'
    assert rival('GET', '/v1/plan').json()['plan']['competition']['name'] == 'Euros'
    assert rival('GET', '/v1/plan').json()['source'] == 'default'


def test_accounts_share_one_database_with_explicit_owners(service):
    athlete, rival = two_accounts(service)
    athlete('POST', '/v1/commands', json=activity('a1', 'Athlete class'))
    assert not (service.data_dir / 'users').exists()
    with service.accounts.connect() as db:
        assert db.execute('SELECT COUNT(*) FROM users').fetchone()[0] == 2
        assert db.execute('SELECT COUNT(DISTINCT user_id) FROM records').fetchone()[0] == 1


def test_mcp_tools_read_the_token_holders_records(service):
    athlete, rival = two_accounts(service)
    athlete('POST', '/v1/commands', json=activity('a1', 'Athlete class'))
    rival('POST', '/v1/commands', json=activity('r1', 'Rival class'))

    def tool(call, name, arguments=None):
        headers = {'Accept': 'application/json, text/event-stream'}
        call('POST', '/mcp', headers=headers, json={
            'jsonrpc': '2.0', 'id': 1, 'method': 'initialize',
            'params': {'protocolVersion': '2025-03-26', 'capabilities': {}, 'clientInfo': {'name': 't', 'version': '1'}}})
        answer = call('POST', '/mcp', headers=headers, json={
            'jsonrpc': '2.0', 'id': 2, 'method': 'tools/call',
            'params': {'name': name, 'arguments': arguments or {}}}).json()['result']
        return answer.get('structuredContent') or json.loads(answer['content'][0]['text'])

    assert [r['entity_id'] for r in tool(athlete, 'read_records')['records']] == ['a1']
    assert [r['entity_id'] for r in tool(rival, 'read_records')['records']] == ['r1']
    assert tool(rival, 'read_training_plan')['plan']['competition']['name'] == 'Euros'


def test_tracker_imports_are_per_account(service):
    athlete, rival = two_accounts(service)
    workout = json.loads((Path(__file__).resolve().parents[2] / 'contracts' / 'tracker-import.json').read_text())
    assert athlete('POST', '/v1/tracker/import', json=workout).status_code == 200
    window = '?date_from=2026-10-12&date_to=2026-10-12'
    assert len(athlete('GET', '/v1/tracker/workouts' + window).json()['workouts']) == 1
    assert rival('GET', '/v1/tracker/workouts' + window).json()['workouts'] == []
    # The same source workout may be imported by another account without a duplicate-identity error.
    assert rival('POST', '/v1/tracker/import', json=workout).status_code == 200
