"""Operator commands: report, details, disable/enable and delete accounts."""
import json
import sys

import pytest

from conftest import PASSWORD
from euros_server import app as service_app
from euros_server.accounts import AccountStore
from euros_server.domain import DomainError, Store
from euros_server.security import TokenBudget

ACTIVITY = {'command_id': 'c1', 'entity_type': 'activities', 'entity_id': 'a1', 'expected_revision': 0,
            'operation': 'upsert', 'payload': {'title': 'Secret class name', 'kind': 'bjj',
                                               'scheduled_at': '2026-11-20T18:00:00-06:00', 'status': 'pending'}}


@pytest.fixture
def deployment(tmp_path, monkeypatch):
    accounts = AccountStore(tmp_path / 'euros.db', iterations=1000)
    athlete = accounts.users.create('athlete', PASSWORD, 'Sam')
    accounts.users.create('rival', PASSWORD)
    Store(accounts.path, athlete['user_id']).command(ACTIVITY)
    budget = TokenBudget(accounts.path)
    budget.settle(budget.reserve(athlete['user_id'], 'food_draft'), 900)
    monkeypatch.setenv('EUROS_DATA_DIR', str(tmp_path))
    monkeypatch.delenv('EUROS_DATABASE_URL', raising=False)
    monkeypatch.delenv('EUROS_DATABASE_URL_FILE', raising=False)
    monkeypatch.delenv('EUROS_MODEL_TOKEN_BUDGET', raising=False)
    monkeypatch.delenv('EUROS_ACCOUNT_TOKEN_BUDGET', raising=False)
    return accounts


def run(monkeypatch, capsys, *args):
    monkeypatch.setattr(sys, 'argv', ['euros-server', *args])
    service_app.main()
    return json.loads(capsys.readouterr().out)


def test_report_lists_accounts_activity_and_spend_without_content(deployment, monkeypatch, capsys):
    report = run(monkeypatch, capsys, '--account-report', '--model-token-budget', '2000000',
                 '--account-token-budget', '400000')
    assert report['model_budget'] == {'monthly_tokens': 2000000, 'spent_tokens': 900,
                                      'account_monthly_tokens': 400000}
    by_name = {a['username']: a for a in report['accounts']}
    assert set(by_name) == {'athlete', 'rival'}
    assert by_name['athlete']['records'] == 1 and by_name['athlete']['model_tokens_this_month'] == 900
    assert by_name['rival']['model_calls_this_month'] == 0
    assert 'Secret class name' not in json.dumps(report)


def test_details_show_one_accounts_rows(deployment, monkeypatch, capsys):
    details = run(monkeypatch, capsys, '--account-details', 'athlete')
    assert details['username'] == 'athlete' and details['rows']['records'] == 1
    assert details['model_usage_this_month'] == {'calls': 1, 'tokens': 900}
    assert 'password_hash' not in details


def test_disable_blocks_sign_in_and_enable_restores_it(deployment, monkeypatch, capsys):
    assert run(monkeypatch, capsys, '--disable-account', 'athlete') == {'username': 'athlete', 'disabled': True}
    with pytest.raises(DomainError, match='invalid_credentials'):
        deployment.users.authenticate('athlete', PASSWORD)
    # Disabled accounts still appear in the operator report.
    report = run(monkeypatch, capsys, '--account-report')
    assert {a['username']: a['disabled'] for a in report['accounts']}['athlete'] is True
    assert run(monkeypatch, capsys, '--enable-account', 'athlete')['disabled'] is False
    assert deployment.users.authenticate('athlete', PASSWORD)['username'] == 'athlete'


def test_delete_requires_the_repeated_username(deployment, monkeypatch, capsys):
    monkeypatch.setattr(sys, 'argv', ['euros-server', '--delete-account', 'athlete'])
    with pytest.raises(ValueError, match='--confirm'):
        service_app.main()
    monkeypatch.setattr(sys, 'argv', ['euros-server', '--delete-account', 'athlete', '--confirm', 'rival'])
    with pytest.raises(ValueError, match='--confirm'):
        service_app.main()
    assert deployment.users.by_username('athlete') is not None
    removed = run(monkeypatch, capsys, '--delete-account', 'athlete', '--confirm', 'athlete')
    assert removed['username'] == 'athlete' and removed['rows']['records'] == 1
    assert deployment.users.by_username('athlete') is None
    report = run(monkeypatch, capsys, '--account-report')
    assert [a['username'] for a in report['accounts']] == ['rival']
    # The deleted account's spend still counts against this month's budget.
    assert report['model_budget']['spent_tokens'] == 900
    assert report['deleted_or_legacy_accounts'] == {'calls': 1, 'tokens': 900}


def test_budget_comes_from_the_environment(deployment, monkeypatch, capsys):
    monkeypatch.setenv('EUROS_MODEL_TOKEN_BUDGET', '12345')
    monkeypatch.setenv('EUROS_ACCOUNT_TOKEN_BUDGET', '678')
    budget = run(monkeypatch, capsys, '--account-report')['model_budget']
    assert (budget['monthly_tokens'], budget['account_monthly_tokens']) == (12345, 678)
    monkeypatch.delenv('EUROS_MODEL_TOKEN_BUDGET')
    monkeypatch.delenv('EUROS_ACCOUNT_TOKEN_BUDGET')
    budget = run(monkeypatch, capsys, '--account-report')['model_budget']
    assert (budget['monthly_tokens'], budget['account_monthly_tokens']) == (5_000_000, 1_000_000)
    monkeypatch.setenv('EUROS_MODEL_TOKEN_BUDGET', 'lots')
    monkeypatch.setattr(sys, 'argv', ['euros-server', '--account-report'])
    with pytest.raises(ValueError, match='whole number'):
        service_app.main()
