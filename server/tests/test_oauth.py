"""Signing in to the app API and MCP with OAuth."""
from urllib.parse import parse_qs, urlsplit

import pytest

from conftest import PASSWORD, REDIRECT
from euros_server.domain import DomainError
from euros_server.oauth_store import s256, valid_redirect_uri, validate_scope

ACTIVITY = {'command_id': 'c1', 'entity_type': 'activities', 'entity_id': 'a1', 'expected_revision': 0,
            'operation': 'upsert', 'payload': {'title': 'Practice', 'kind': 'bjj',
                                               'scheduled_at': '2026-11-20T18:00:00-06:00', 'status': 'pending'}}


def query(response):
    return {k: v[0] for k, v in parse_qs(urlsplit(response.headers['location']).query).items()}


def test_redirect_uris_and_scopes_are_restricted():
    assert valid_redirect_uri('https://euros.example/callback')
    assert valid_redirect_uri('http://127.0.0.1:7777/cb') and valid_redirect_uri('com.gwensmuda.euros:/oauth')
    for bad in ('http://evil.example/cb', 'https://euros.example/cb#fragment', 'https://*.example/cb',
                'https://user:pw@euros.example/cb', 'ftp://euros.example', 42):
        assert not valid_redirect_uri(bad)
    assert validate_scope(None) == 'training.read training.write'
    assert validate_scope('training.read') == 'training.read'
    with pytest.raises(DomainError, match='invalid_scope'):
        validate_scope('training.read admin')


def test_discovery_documents_describe_this_server(service):
    metadata = service.client.get('/.well-known/oauth-authorization-server').json()
    assert metadata['issuer'] == 'http://localhost'
    assert metadata['authorization_endpoint'] == 'http://localhost/oauth/authorize'
    assert metadata['code_challenge_methods_supported'] == ['S256']
    assert metadata['grant_types_supported'] == ['authorization_code', 'refresh_token']
    resource = service.client.get('/.well-known/oauth-protected-resource/mcp').json()
    assert resource['authorization_servers'] == ['http://localhost']
    assert resource['scopes_supported'] == ['training.read', 'training.write']


def test_unauthenticated_request_points_at_the_metadata(service):
    denied = service.client.get('/v1/records')
    assert denied.status_code == 401
    challenge = denied.headers['www-authenticate']
    assert 'resource_metadata="http://localhost/.well-known/oauth-protected-resource"' in challenge


def test_registration_rejects_addresses_it_cannot_trust(service):
    assert service.client.post('/oauth/register', json={'redirect_uris': ['http://evil.example/cb']}).status_code == 400
    assert service.client.post('/oauth/register', json={'redirect_uris': []}).status_code == 400
    public = service.register_client()
    assert public['client_id'].startswith('cli-') and 'client_secret' not in public
    confidential = service.client.post('/oauth/register', json={
        'client_name': 'Server side', 'redirect_uris': ['https://euros.example/cb'],
        'token_endpoint_auth_method': 'client_secret_post'}).json()
    assert confidential['client_secret']


def test_code_flow_signs_in_and_reaches_only_that_account(service):
    service.signup('athlete')
    service.logout()
    client, tokens = service.connect('athlete')
    assert tokens['token_type'] == 'Bearer' and tokens['scope'] == 'training.read training.write'
    call = service.api(tokens['access_token'])
    assert call('POST', '/v1/commands', json=ACTIVITY).status_code == 200
    assert call('GET', '/v1/records').json()['records'][0]['entity_id'] == 'a1'
    assert service.client.get('/v1/records').status_code == 401


def test_pkce_is_enforced(service):
    service.signup('athlete')
    service.logout()
    client = service.register_client()
    code = service.code_from(service.authorize(client['client_id'], 'athlete'))
    wrong = service.exchange(client['client_id'], code, verifier='w' * 64)
    assert wrong.status_code == 400 and wrong.json()['error'] == 'invalid_grant'
    # The code is spent even by a failed attempt, so a stolen code cannot be retried.
    assert service.exchange(client['client_id'], code).status_code == 400


def test_authorization_requires_s256_and_a_registered_address(service):
    service.signup('athlete')
    client = service.register_client()
    base = {'response_type': 'code', 'client_id': client['client_id'], 'redirect_uri': REDIRECT,
            'code_challenge': s256('v' * 64)}
    plain = service.client.get('/oauth/authorize', params={**base, 'code_challenge_method': 'plain'},
                               follow_redirects=False)
    assert plain.status_code == 303 and query(plain)['error'] == 'invalid_request'
    elsewhere = service.client.get('/oauth/authorize', params={**base, 'code_challenge_method': 'S256',
                                                               'redirect_uri': 'https://evil.example/cb'})
    assert elsewhere.status_code == 400 and 'Location' not in elsewhere.headers
    unknown = service.client.get('/oauth/authorize', params={**base, 'client_id': 'cli-nope',
                                                             'code_challenge_method': 'S256'})
    assert unknown.status_code == 400


def test_denying_returns_the_user_to_the_client_without_a_code(service):
    service.signup('athlete')
    client = service.register_client()
    params = {'response_type': 'code', 'client_id': client['client_id'], 'redirect_uri': REDIRECT,
              'code_challenge': s256('v' * 64), 'code_challenge_method': 'S256', 'state': 'xyz'}
    service.client.get('/oauth/authorize', params=params)
    denied = service.client.post('/oauth/authorize', data={
        **params, 'form_token': service.client.cookies.get('euros_form'), 'decision': 'deny'},
        follow_redirects=False)
    assert query(denied) == {'error': 'access_denied', 'state': 'xyz'}


def test_wrong_password_at_the_authorization_screen_issues_nothing(service):
    service.signup('athlete')
    service.logout()
    client = service.register_client()
    refused = service.authorize(client['client_id'], 'athlete', password='not my password')
    assert refused.status_code == 401 and 'Wrong username or password' in refused.text
    assert 'location' not in refused.headers


def test_refresh_rotates_and_reuse_revokes_the_grant(service):
    service.signup('athlete')
    service.logout()
    client, tokens = service.connect('athlete')
    refreshed = service.client.post('/oauth/token', data={
        'grant_type': 'refresh_token', 'refresh_token': tokens['refresh_token'],
        'client_id': client['client_id']})
    assert refreshed.status_code == 200
    new_tokens = refreshed.json()
    assert new_tokens['refresh_token'] != tokens['refresh_token']
    assert service.api(new_tokens['access_token'])('GET', '/v1/records').status_code == 200
    replay = service.client.post('/oauth/token', data={
        'grant_type': 'refresh_token', 'refresh_token': tokens['refresh_token'],
        'client_id': client['client_id']})
    assert replay.status_code == 400
    # Reuse of a spent refresh token ends the whole grant, including the token just issued.
    assert service.api(new_tokens['access_token'])('GET', '/v1/records').status_code == 401


def test_read_only_tokens_cannot_write(service):
    service.signup('athlete')
    service.logout()
    client, tokens = service.connect('athlete', scope='training.read')
    assert tokens['scope'] == 'training.read'
    call = service.api(tokens['access_token'])
    assert call('GET', '/v1/records').status_code == 200
    denied = call('POST', '/v1/commands', json=ACTIVITY)
    assert denied.status_code == 403 and denied.json()['error'] == 'insufficient_scope'


def test_revocation_takes_effect_immediately(service):
    service.signup('athlete')
    service.logout()
    client, tokens = service.connect('athlete')
    assert service.client.post('/oauth/revoke', data={'token': tokens['access_token'],
                                                      'client_id': client['client_id']}).status_code == 200
    assert service.api(tokens['access_token'])('GET', '/v1/records').status_code == 401
    assert service.client.post('/oauth/token', data={'grant_type': 'refresh_token',
                                                     'refresh_token': tokens['refresh_token'],
                                                     'client_id': client['client_id']}).status_code == 400


def test_mcp_requires_a_token_and_serves_the_token_holder(service):
    service.signup('athlete')
    service.logout()
    _, tokens = service.connect('athlete')
    headers = {'Authorization': f'Bearer {tokens["access_token"]}',
               'Accept': 'application/json, text/event-stream'}
    assert service.client.post('/mcp', json={'jsonrpc': '2.0', 'id': 1, 'method': 'initialize',
                                             'params': {'protocolVersion': '2025-03-26', 'capabilities': {},
                                                        'clientInfo': {'name': 't', 'version': '1'}}}).status_code == 401
    init = service.client.post('/mcp', headers=headers, json={
        'jsonrpc': '2.0', 'id': 1, 'method': 'initialize',
        'params': {'protocolVersion': '2025-03-26', 'capabilities': {}, 'clientInfo': {'name': 't', 'version': '1'}}})
    assert init.status_code == 200, init.text
    saved = service.client.post('/mcp', headers=headers, json={
        'jsonrpc': '2.0', 'id': 2, 'method': 'tools/call',
        'params': {'name': 'apply_command', 'arguments': {'command': ACTIVITY}}}).json()['result']
    assert not saved.get('isError'), saved
    assert service.api(tokens['access_token'])('GET', '/v1/records').json()['records'][0]['entity_id'] == 'a1'


def test_a_read_only_token_cannot_write_through_mcp_either(service):
    service.signup('athlete')
    service.logout()
    _, tokens = service.connect('athlete', scope='training.read')
    call = service.api(tokens['access_token'])
    headers = {'Accept': 'application/json, text/event-stream'}
    call('POST', '/mcp', headers=headers, json={
        'jsonrpc': '2.0', 'id': 1, 'method': 'initialize',
        'params': {'protocolVersion': '2025-03-26', 'capabilities': {}, 'clientInfo': {'name': 't', 'version': '1'}}})
    refused = call('POST', '/mcp', headers=headers, json={
        'jsonrpc': '2.0', 'id': 2, 'method': 'tools/call',
        'params': {'name': 'apply_command', 'arguments': {'command': ACTIVITY}}}).json()['result']
    assert refused['isError'] is True and 'insufficient_scope' in refused['content'][0]['text']
    reads = call('POST', '/mcp', headers=headers, json={
        'jsonrpc': '2.0', 'id': 3, 'method': 'tools/call',
        'params': {'name': 'read_records', 'arguments': {}}}).json()['result']
    assert not reads.get('isError')
    assert call('GET', '/v1/records').json()['records'] == []
