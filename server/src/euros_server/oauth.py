"""OAuth 2.1 authorization server for the phone app and MCP clients.

Authorization code with PKCE (S256 only) is the single flow; there is no implicit grant, no
password grant and no token that a client can mint without the user signing in on this site.
Refresh tokens rotate, and a replayed code or refresh token revokes the whole grant.
Dynamic client registration (RFC 7591) is open, because an MCP client has to register before
anyone can sign in; registration alone grants no access to any account's records.
"""
import base64
import json
from html import escape
from urllib.parse import unquote, urlencode, urlsplit, urlunsplit

from starlette.responses import JSONResponse, RedirectResponse
from starlette.routing import Route

from .oauth_store import AUTH_METHODS, SCOPES, validate_scope
from .domain import DomainError
from .pages import field, hidden, notice, page
from .browser import base_url


def _issuer(request, configured=None):
    """The public base URL clients must see in metadata, links and redirects."""
    return base_url(request, configured)


def _redirect_with(uri, params):
    parts = urlsplit(uri)
    query = urlencode([(k, v) for k, v in params.items() if v is not None])
    merged = f'{parts.query}&{query}' if parts.query else query
    return urlunsplit((parts.scheme, parts.netloc, parts.path, merged, ''))


class OAuthServer:
    def __init__(self, accounts, sessions, issuer=None):
        self.accounts, self.sessions, self.configured_issuer = accounts, sessions, issuer

    # --- discovery ---------------------------------------------------------------------

    async def authorization_metadata(self, request):
        base = _issuer(request, self.configured_issuer)
        return JSONResponse({
            'issuer': base,
            'authorization_endpoint': f'{base}/oauth/authorize',
            'token_endpoint': f'{base}/oauth/token',
            'registration_endpoint': f'{base}/oauth/register',
            'revocation_endpoint': f'{base}/oauth/revoke',
            'scopes_supported': list(SCOPES),
            'response_types_supported': ['code'],
            'response_modes_supported': ['query'],
            'grant_types_supported': ['authorization_code', 'refresh_token'],
            'code_challenge_methods_supported': ['S256'],
            'token_endpoint_auth_methods_supported': list(AUTH_METHODS),
            'revocation_endpoint_auth_methods_supported': list(AUTH_METHODS),
        }, headers={'Cache-Control': 'no-store'})

    async def resource_metadata(self, request):
        base = _issuer(request, self.configured_issuer)
        return JSONResponse({
            'resource': base,
            'authorization_servers': [base],
            'scopes_supported': list(SCOPES),
            'bearer_methods_supported': ['header'],
            'resource_documentation': f'{base}/account/',
        }, headers={'Cache-Control': 'no-store'})

    # --- registration ------------------------------------------------------------------

    async def register(self, request):
        try:
            body = json.loads(await request.body() or b'{}')
        except ValueError:
            raise DomainError('invalid_client_metadata', 400) from None
        client = self.accounts.oauth.register_client(body)
        return JSONResponse({'client_id': client['client_id'],
                             **({'client_secret': client['client_secret']} if client['client_secret'] else {}),
                             'client_name': client['name'], 'redirect_uris': client['redirect_uris'],
                             'token_endpoint_auth_method': client['auth_method'],
                             'grant_types': ['authorization_code', 'refresh_token'],
                             'response_types': ['code']},
                            status_code=201, headers={'Cache-Control': 'no-store'})

    # --- authorization -----------------------------------------------------------------

    def _request_params(self, params):
        """Validated authorization parameters. Client and redirect URI are checked first, because
        everything else may only be reported by redirecting to an address this client owns."""
        client = self.accounts.oauth.client(params.get('client_id', ''))
        redirect_uri = params.get('redirect_uri')
        if client is None:
            raise DomainError('invalid_client', 400, error_description='Unknown client_id')
        if redirect_uri is None and len(client['redirect_uris']) == 1:
            redirect_uri = client['redirect_uris'][0]
        if redirect_uri not in client['redirect_uris']:
            raise DomainError('invalid_redirect_uri', 400,
                              error_description='redirect_uri does not match this registration')
        return client, redirect_uri

    @staticmethod
    def _flow(params, client, redirect_uri):
        if params.get('response_type') != 'code':
            raise DomainError('unsupported_response_type', 400)
        if params.get('code_challenge_method') != 'S256':
            raise DomainError('invalid_request', 400, error_description='code_challenge_method must be S256')
        challenge = params.get('code_challenge', '')
        if not isinstance(challenge, str) or not 43 <= len(challenge) <= 128:
            raise DomainError('invalid_request', 400, error_description='code_challenge')
        state = params.get('state')
        if state is not None and (not isinstance(state, str) or len(state) > 500):
            raise DomainError('invalid_request', 400, error_description='state')
        resource = params.get('resource')
        if resource is not None and (not isinstance(resource, str) or len(resource) > 500):
            raise DomainError('invalid_request', 400, error_description='resource')
        return dict(client_id=client['client_id'], redirect_uri=redirect_uri, challenge=challenge,
                    scope=validate_scope(params.get('scope')), state=state, resource=resource)

    def _consent_page(self, request, client, flow, user, message=None, status=200):
        """Sign in, or confirm the account already signed in on this browser."""
        carried = ''.join(hidden(name, value) for name, value in (
            ('response_type', 'code'), ('client_id', flow['client_id']), ('redirect_uri', flow['redirect_uri']),
            ('code_challenge', flow['challenge']), ('code_challenge_method', 'S256'),
            ('scope', flow['scope']), ('state', flow['state'] or ''), ('resource', flow['resource'] or '')))
        token, cookie = self.sessions.form_token(request)
        permissions = ''.join(f'<li>{"Read your training plan and records" if s == "training.read" else "Save and change your training records"}</li>'
                              for s in flow['scope'].split())
        name = escape(client['name'])
        if user:
            body = (f'{notice(message, "error") if message else ""}'
                    f'<p><strong>{name}</strong> is asking to use your Euros account.</p>'
                    f'<ul>{permissions}</ul>'
                    f'<form method="post" action="/oauth/authorize">{carried}{hidden("form_token", token)}'
                    f'<p class="muted">Signed in as {escape(user["username"])}.</p>'
                    f'<button name="decision" value="allow">Allow {escape(_short(client["name"]))}</button> '
                    f'<button class="quiet" name="decision" value="deny">Deny</button></form>'
                    f'<form method="post" action="/account/logout">{hidden("csrf", self.sessions.csrf(request) or "")}'
                    f'{hidden("next", _return_to(flow))}<button class="quiet">Use another account</button></form>')
        else:
            body = (f'{notice(message, "error") if message else ""}'
                    f'<p><strong>{name}</strong> is asking to use your Euros account.</p>'
                    f'<ul>{permissions}</ul>'
                    f'<form method="post" action="/oauth/authorize">{carried}{hidden("form_token", token)}'
                    f'{field("Username", "username")}{field("Password", "password", kind="password")}'
                    f'<button name="decision" value="allow">Sign in and allow</button> '
                    f'<button class="quiet" name="decision" value="deny">Cancel</button></form>')
        response = page('Authorize access', body, status=status)
        self.sessions.attach_form_cookie(request, response, cookie)
        return response

    async def authorize(self, request):
        params = dict(request.query_params)
        if request.method == 'POST':
            params = dict(await request.form())
        try:
            client, redirect_uri = self._request_params(params)
        except DomainError as exc:
            return page('Authorization failed', notice(_describe(exc), 'error'), status=exc.status)
        try:
            flow = self._flow(params, client, redirect_uri)
        except DomainError as exc:
            return RedirectResponse(_redirect_with(redirect_uri, {'error': exc.body['error'],
                                                                  'error_description': exc.body.get('error_description'),
                                                                  'state': params.get('state')}), status_code=303)
        session = self.sessions.current(request)
        user = session['user'] if session else None
        if request.method == 'GET':
            return self._consent_page(request, client, flow, user)
        if not self.sessions.valid_form(request, params.get('form_token')):
            return self._consent_page(request, client, flow, user, 'That form expired. Try again.', status=400)
        if params.get('decision') != 'allow':
            return RedirectResponse(_redirect_with(redirect_uri, {'error': 'access_denied', 'state': flow['state']}),
                                    status_code=303)
        if not user:
            try:
                user = self.accounts.users.authenticate(params.get('username', ''), params.get('password', ''))
            except DomainError as exc:
                return self._consent_page(request, client, flow, None, _describe(exc), status=exc.status)
        code = self.accounts.oauth.create_code(client['client_id'], user['user_id'], redirect_uri,
                                         flow['challenge'], flow['scope'], flow['resource'])
        target = _redirect_with(redirect_uri, {'code': code, 'state': flow['state']})
        response = RedirectResponse(target, status_code=303)
        if session is None:
            # Signing in here also signs in to the account site on this browser.
            self.sessions.start(request, response, user['user_id'])
        self.sessions.clear_form_cookie(response)
        return response

    # --- tokens ------------------------------------------------------------------------

    def _client_credentials(self, request, form):
        client_id = form.get('client_id')
        secret = form.get('client_secret')
        header = request.headers.get('authorization', '')
        if header.lower().startswith('basic '):
            try:
                decoded = base64.b64decode(header.split(' ', 1)[1]).decode()
                client_id, secret = (unquote(part) for part in decoded.split(':', 1))
            except (ValueError, UnicodeDecodeError):
                raise DomainError('invalid_client', 401) from None
        if not isinstance(client_id, str) or not client_id:
            raise DomainError('invalid_client', 401)
        self.accounts.oauth.check_client_secret(client_id, secret)
        return client_id

    async def token(self, request):
        form = dict(await request.form())
        client_id = self._client_credentials(request, form)
        grant_type = form.get('grant_type')
        if grant_type == 'authorization_code':
            tokens = self.accounts.oauth.redeem_code(form.get('code'), client_id, form.get('redirect_uri'),
                                               form.get('code_verifier'))
        elif grant_type == 'refresh_token':
            tokens = self.accounts.oauth.refresh(form.get('refresh_token'), client_id)
        else:
            raise DomainError('unsupported_grant_type', 400)
        return JSONResponse(tokens, headers={'Cache-Control': 'no-store', 'Pragma': 'no-cache'})

    async def revoke(self, request):
        form = dict(await request.form())
        self._client_credentials(request, form)
        self.accounts.oauth.revoke_token(form.get('token'))
        return JSONResponse({}, headers={'Cache-Control': 'no-store'})

    def routes(self):
        return [Route('/.well-known/oauth-authorization-server', self.authorization_metadata),
                Route('/.well-known/openid-configuration', self.authorization_metadata),
                Route('/.well-known/oauth-protected-resource', self.resource_metadata),
                Route('/.well-known/oauth-protected-resource/{path:path}', self.resource_metadata),
                Route('/oauth/register', self.register, methods=['POST']),
                Route('/oauth/authorize', self.authorize, methods=['GET', 'POST']),
                Route('/oauth/token', self.token, methods=['POST']),
                Route('/oauth/revoke', self.revoke, methods=['POST'])]


def _describe(exc):
    return exc.body.get('error_description') or {
        'invalid_client': 'That application is not registered on this server.',
        'invalid_redirect_uri': 'That application sent an address this server does not recognise.',
        'invalid_credentials': 'Wrong username or password.',
        'too_many_attempts': 'Too many sign-in attempts. Try again later.',
    }.get(exc.body['error'], 'The request could not be completed.')


def _short(name):
    return name if len(name) <= 30 else name[:29] + '…'


def _return_to(flow):
    """Where signing in as someone else lands: this same authorization request, never another site."""
    params = {'response_type': 'code', 'client_id': flow['client_id'], 'redirect_uri': flow['redirect_uri'],
              'code_challenge': flow['challenge'], 'code_challenge_method': 'S256', 'scope': flow['scope'],
              'state': flow['state'], 'resource': flow['resource']}
    return '/oauth/authorize?' + urlencode({k: v for k, v in params.items() if v})
