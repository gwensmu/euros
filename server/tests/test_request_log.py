"""One structured line per request, naming the request but never its content."""
import io
import json
import logging

import pytest
from starlette.testclient import TestClient

from conftest import SilentProvider
from euros_server.app import create_app
from euros_server.domain import Store
from euros_server.llm import ModelService
from euros_server.observability import JsonFormatter, configure_logging

API, MCP = 'a' * 40, 'm' * 40


@pytest.fixture
def lines(caplog):
    caplog.set_level(logging.INFO, logger='euros.request')

    def read():
        return [dict(r.fields, msg=r.getMessage(), level=r.levelname, exc=bool(r.exc_info))
                for r in caplog.records if r.name == 'euros.request']
    return read


@pytest.fixture
def client(tmp_path):
    app = create_app(Store(tmp_path / 'db'), API, MCP, model_service=ModelService(SilentProvider()))
    with TestClient(app, base_url='http://localhost', raise_server_exceptions=False) as found:
        yield found


def test_a_request_line_names_the_account_without_content(client, lines):
    response = client.get('/v1/nutrition?local_date=2026-11-19', headers={'Authorization': f'Bearer {API}'})
    entry = lines()[-1]
    assert entry['request_id'] == response.headers['x-request-id']
    assert entry['method'] == 'GET' and entry['path'] == '/v1/nutrition'
    assert entry['status'] == 200 and entry['duration_ms'] >= 0
    assert entry['user_id'].startswith('usr-') and entry['client'] == 'static-api' and entry['auth'] == 'static'
    text = json.dumps(lines())
    assert '2026-11-19' not in text and API not in text


def test_a_refused_request_is_logged_without_an_account(client, lines):
    assert client.get('/v1/records').status_code == 401
    entry = lines()[-1]
    assert entry['status'] == 401 and 'user_id' not in entry


def test_command_bodies_never_reach_the_log(client, lines):
    secret = 'grilled salmon with rice'
    client.post('/v1/commands', headers={'Authorization': f'Bearer {API}'}, json={'payload': {'title': secret}})
    assert secret not in json.dumps(lines())


def test_mcp_lines_name_the_tool_but_not_its_arguments(client, lines):
    headers = {'Authorization': f'Bearer {MCP}', 'Accept': 'application/json, text/event-stream'}
    client.post('/mcp', headers=headers, json={'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call',
                                               'params': {'name': 'nutrition_totals',
                                                          'arguments': {'local_date': '2026-11-19'}}})
    entry = lines()[-1]
    assert entry['rpc_method'] == 'tools/call' and entry['tool'] == 'nutrition_totals'
    assert entry['client'] == 'static-mcp'
    assert '2026-11-19' not in json.dumps(lines())


def test_an_unhandled_error_is_logged_with_its_traceback_and_request_id(client, lines, monkeypatch):
    def broken(self, entity_type=None):
        raise RuntimeError('database fell over')
    monkeypatch.setattr(Store, 'records', broken)
    response = client.get('/v1/records', headers={'Authorization': f'Bearer {API}'})
    assert response.status_code == 500
    entry = lines()[-1]
    assert entry['level'] == 'ERROR' and entry['exc'] and entry['status'] == 500
    assert entry['request_id'] == response.headers['x-request-id']


def test_slow_requests_are_warnings(client, lines, monkeypatch):
    import euros_server.observability as observability
    monkeypatch.setattr(observability, 'SLOW_MS', 0)
    client.get('/v1/records', headers={'Authorization': f'Bearer {API}'})
    assert lines()[-1]['level'] == 'WARNING'


def test_passing_health_checks_stay_quiet(client, lines):
    assert client.get('/healthz').status_code == 200
    assert lines() == []


def test_account_site_lines_name_the_signed_in_account(service, caplog):
    caplog.set_level(logging.INFO, logger='euros.request')
    service.signup('athlete')
    service.client.get('/account/plan')
    entry = [r.fields for r in caplog.records if r.name == 'euros.request'][-1]
    user = service.accounts.users.by_username('athlete')
    assert entry['path'] == '/account/plan' and entry['user_id'] == user['user_id'] and entry['auth'] == 'session'
    assert 'athlete' not in json.dumps(entry)


def test_configured_output_is_one_json_object_per_line():
    stream = io.StringIO()
    logger = logging.getLogger('euros')
    saved = (list(logger.handlers), logger.level, logger.propagate)
    try:
        logger.handlers = []
        configure_logging('info', stream)
        configure_logging('info', stream)  # Idempotent: one handler.
        logging.getLogger('euros.request').info('request', extra={'fields': {'status': 204}})
        written = stream.getvalue().splitlines()
        assert len(written) == 1
        entry = json.loads(written[0])
        assert entry['status'] == 204 and entry['level'] == 'info' and entry['msg'] == 'request'
    finally:
        logger.handlers, logger.level, logger.propagate = saved


def test_formatter_includes_the_exception():
    try:
        raise ValueError('boom')
    except ValueError:
        import sys
        record = logging.LogRecord('euros.request', logging.ERROR, __file__, 1, 'request failed', None, sys.exc_info())
    entry = json.loads(JsonFormatter().format(record))
    assert entry['exc']['type'] == 'ValueError'
    assert entry['exc']['frames'][-1]['function'] == 'test_formatter_includes_the_exception'
    assert 'boom' not in json.dumps(entry)


def test_formatter_omits_database_parameters_and_exception_chains():
    import sys
    from sqlalchemy.exc import StatementError
    private = 'private meal and credential value'
    try:
        try:
            raise ValueError(private)
        except ValueError as cause:
            raise StatementError('failed', 'INSERT INTO records VALUES (?)', [private], cause) from cause
    except StatementError:
        record = logging.LogRecord('euros.request', logging.ERROR, __file__, 1,
                                   'request failed', None, sys.exc_info())
    written = JsonFormatter().format(record)
    assert private not in written and 'INSERT INTO' not in written
    assert json.loads(written)['exc']['type'] == 'StatementError'
