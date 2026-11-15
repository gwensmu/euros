"""Shared fixtures for the multi-account service: real accounts, no model calls."""
import re
from urllib.parse import parse_qs, urlsplit

import pytest
from starlette.testclient import TestClient

from euros_server.accounts import AccountStore
from euros_server.oauth_store import s256
from euros_server.app import create_service
from euros_server.llm import ModelService

PASSWORD = 'correct horse battery'
REDIRECT = 'com.gwensmuda.euros:/oauth2redirect'


class SilentProvider:
    async def generate(self, **kwargs):
        raise AssertionError('these tests never call a model')


class Service:
    """A running service plus the few flows every account test needs."""

    def __init__(self, client, accounts, data_dir):
        self.client, self.accounts, self.data_dir = client, accounts, data_dir

    def signup(self, username, password=PASSWORD, display_name=''):
        page = self.client.get('/account/signup')
        return self.client.post('/account/signup', data={
            'form_token': self.client.cookies.get('euros_form'), 'username': username,
            'display_name': display_name, 'password': password, 'password2': password},
            follow_redirects=False), page

    def login(self, username, password=PASSWORD):
        self.client.get('/account/login')
        return self.client.post('/account/login', data={
            'form_token': self.client.cookies.get('euros_form'),
            'username': username, 'password': password}, follow_redirects=False)

    def logout(self):
        self.client.post('/account/logout', data={'csrf': self.csrf('/account/')}, follow_redirects=False)
        self.client.cookies.clear()

    def csrf(self, path='/account/plan'):
        found = re.search('name="csrf" value="([^"]+)"', self.client.get(path).text)
        return found.group(1) if found else ''

    def register_client(self, name='Test client', uris=(REDIRECT,)):
        response = self.client.post('/oauth/register', json={'client_name': name, 'redirect_uris': list(uris)})
        assert response.status_code == 201, response.text
        return response.json()

    def authorize(self, client_id, username, password=PASSWORD, scope=None, verifier='v' * 64,
                  redirect_uri=REDIRECT, state='xyz'):
        """Sign in at the authorization endpoint and return the redirect it answers with."""
        params = {'response_type': 'code', 'client_id': client_id, 'redirect_uri': redirect_uri,
                  'code_challenge': s256(verifier), 'code_challenge_method': 'S256', 'state': state}
        if scope:
            params['scope'] = scope
        self.client.get('/oauth/authorize', params=params)
        form = {**params, 'form_token': self.client.cookies.get('euros_form'), 'decision': 'allow'}
        if username:
            form.update(username=username, password=password)
        return self.client.post('/oauth/authorize', data=form, follow_redirects=False)

    @staticmethod
    def code_from(response):
        assert response.status_code == 303, response.text
        return parse_qs(urlsplit(response.headers['location']).query).get('code', [None])[0]

    def exchange(self, client_id, code, verifier='v' * 64, redirect_uri=REDIRECT):
        return self.client.post('/oauth/token', data={
            'grant_type': 'authorization_code', 'code': code, 'client_id': client_id,
            'redirect_uri': redirect_uri, 'code_verifier': verifier})

    def connect(self, username, password=PASSWORD, scope=None, name='Test client'):
        """Register, authorize and exchange: the tokens a signed-in client would hold."""
        client = self.register_client(name)
        code = self.code_from(self.authorize(client['client_id'], username, password, scope))
        tokens = self.exchange(client['client_id'], code)
        assert tokens.status_code == 200, tokens.text
        self.logout()
        return client, tokens.json()

    def api(self, token):
        def call(method, path, headers=None, **kwargs):
            return self.client.request(method, path,
                                       headers={'Authorization': f'Bearer {token}', **(headers or {})}, **kwargs)
        return call


@pytest.fixture
def service(tmp_path):
    accounts = AccountStore(tmp_path / 'euros.db', iterations=1000)
    app = create_service(tmp_path, model_service=ModelService(SilentProvider()), accounts=accounts,
                         allow_signup=True)
    with TestClient(app, base_url='http://localhost') as client:
        yield Service(client, accounts, tmp_path)
