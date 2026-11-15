"""OAuth clients, authorization codes, grants and tokens.

What the authorization server stores and the rules it enforces on that state; the HTTP
endpoints that call it are in oauth.py. Codes are single use, refresh tokens rotate, and a
credential presented after it was spent is treated as a leak: the whole grant is revoked
rather than extended.
"""
import base64
import hashlib
import hmac
import json
import re
import time
import uuid
from datetime import datetime, timezone
from urllib.parse import urlsplit

from .credentials import digest, new_secret, same_digest
from .domain import DomainError

SCOPES = ('training.read', 'training.write')
DEFAULT_SCOPE = 'training.read training.write'
AUTH_METHODS = ('none', 'client_secret_post', 'client_secret_basic')
CODE_TTL = 300
ACCESS_TTL = 3600
REFRESH_TTL = 60 * 86400
GRANT_TYPES = {'authorization_code', 'refresh_token'}


def s256(verifier):
    """The S256 PKCE challenge for a verifier: base64url of its SHA-256, without padding."""
    return base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')


def validate_scope(requested):
    """The requested scope, defaulting to the full training scope; unknown scopes are refused."""
    if requested is None or (isinstance(requested, str) and not requested.strip()):
        return DEFAULT_SCOPE
    if not isinstance(requested, str) or len(requested) > 200:
        raise DomainError('invalid_scope', 400)
    asked = requested.split()
    if not asked or any(s not in SCOPES for s in asked):
        raise DomainError('invalid_scope', 400, supported=list(SCOPES))
    return ' '.join(sorted(set(asked), key=SCOPES.index))


def valid_redirect_uri(value):
    """HTTPS, loopback HTTP or a private-use scheme; never a wildcard, fragment or credentials."""
    if not isinstance(value, str) or not 1 <= len(value) <= 500 or '*' in value:
        return False
    parts = urlsplit(value)
    if parts.fragment or '@' in (parts.netloc or ''):
        return False
    if parts.scheme == 'https':
        return bool(parts.hostname)
    if parts.scheme == 'http':
        return parts.hostname in ('127.0.0.1', '::1', 'localhost')
    # Private-use scheme for a native app, e.g. com.gwensmuda.euros:/oauth2redirect.
    return bool(re.fullmatch(r'[a-z][a-z0-9+.-]*\.[a-z0-9+.-]+', parts.scheme)) and not parts.netloc


class OAuthStore:
    def __init__(self, database, users):
        self.database, self.users = database, users
        with database.connect() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS oauth_clients (
                  client_id TEXT PRIMARY KEY, secret_hash TEXT, name TEXT NOT NULL, uris TEXT NOT NULL,
                  auth_method TEXT NOT NULL, created_at REAL NOT NULL, registered INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS oauth_codes (
                  code_hash TEXT PRIMARY KEY, client_id TEXT NOT NULL, user_id TEXT NOT NULL,
                  redirect_uri TEXT NOT NULL, challenge TEXT NOT NULL, scope TEXT NOT NULL,
                  resource TEXT, grant_id TEXT NOT NULL, expires_at REAL NOT NULL, used INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS oauth_grants (
                  grant_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, client_id TEXT NOT NULL,
                  scope TEXT NOT NULL, created_at REAL NOT NULL, revoked INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS oauth_tokens (
                  token_hash TEXT PRIMARY KEY, kind TEXT NOT NULL, grant_id TEXT NOT NULL,
                  user_id TEXT NOT NULL, client_id TEXT NOT NULL, scope TEXT NOT NULL,
                  created_at REAL NOT NULL, expires_at REAL NOT NULL, revoked INTEGER NOT NULL DEFAULT 0);
                CREATE INDEX IF NOT EXISTS oauth_tokens_grant ON oauth_tokens(grant_id);
            ''')

    # --- clients -----------------------------------------------------------------------

    @staticmethod
    def _client(row):
        if row is None:
            return None
        return dict(client_id=row['client_id'], name=row['name'], redirect_uris=json.loads(row['uris']),
                    auth_method=row['auth_method'], confidential=row['secret_hash'] is not None,
                    created_at=row['created_at'])

    def register_client(self, payload):
        """RFC 7591 dynamic registration: what a connecting MCP client needs before any sign-in.

        Registration is open because it has to be, and grants nothing: a client reaches an
        account only after that account's user signs in to it.
        """
        if not isinstance(payload, dict):
            raise DomainError('invalid_client_metadata', 400)
        uris = payload.get('redirect_uris')
        if not isinstance(uris, list) or not 1 <= len(uris) <= 5 or not all(valid_redirect_uri(u) for u in uris):
            raise DomainError('invalid_redirect_uri', 400,
                              error_description='redirect_uris must be https, loopback http or a private-use scheme')
        name = payload.get('client_name') or 'OAuth client'
        if not isinstance(name, str) or not 1 <= len(name.strip()) <= 120:
            raise DomainError('invalid_client_metadata', 400, error_description='client_name')
        method = payload.get('token_endpoint_auth_method', 'none')
        if method not in AUTH_METHODS:
            raise DomainError('invalid_client_metadata', 400, error_description='token_endpoint_auth_method')
        grants = payload.get('grant_types', sorted(GRANT_TYPES))
        if not isinstance(grants, list) or not set(grants) <= GRANT_TYPES:
            raise DomainError('invalid_client_metadata', 400, error_description='grant_types')
        if 'response_types' in payload and payload['response_types'] != ['code']:
            raise DomainError('invalid_client_metadata', 400, error_description='response_types')
        client_id = 'cli-' + new_secret(16)
        secret = None if method == 'none' else new_secret()
        with self.database.connect() as db:
            db.execute('INSERT INTO oauth_clients VALUES (?,?,?,?,?,?,1)',
                       (client_id, digest(secret) if secret else None, name.strip(),
                        json.dumps(list(uris)), method, time.time()))
        return {**self.client(client_id), 'client_secret': secret}

    def _client_row(self, client_id):
        with self.database.connect() as db:
            return db.execute('SELECT * FROM oauth_clients WHERE client_id=?', (client_id,)).fetchone()

    def client(self, client_id):
        return self._client(self._client_row(client_id))

    def check_client_secret(self, client_id, secret):
        """The client behind a token-endpoint request. A public client proves nothing here; its
        authorization code is bound to a verifier instead."""
        row = self._client_row(client_id)
        if row is None:
            raise DomainError('invalid_client', 401)
        if row['secret_hash'] is not None and not (isinstance(secret, str) and same_digest(secret, row['secret_hash'])):
            raise DomainError('invalid_client', 401)
        return self._client(row)

    # --- authorization codes -----------------------------------------------------------

    def create_code(self, client_id, user_id, redirect_uri, challenge, scope, resource=None):
        """Record the user's decision as a fresh grant and the one-time code that opens it."""
        code, grant_id, now = new_secret(), 'grt-' + uuid.uuid4().hex, time.time()
        with self.database.connect() as db:
            db.execute('DELETE FROM oauth_codes WHERE expires_at<?', (now - CODE_TTL,))
            db.execute('INSERT INTO oauth_grants VALUES (?,?,?,?,?,0)', (grant_id, user_id, client_id, scope, now))
            db.execute('INSERT INTO oauth_codes VALUES (?,?,?,?,?,?,?,?,?,0)',
                       (digest(code), client_id, user_id, redirect_uri, challenge, scope, resource,
                        grant_id, now + CODE_TTL))
        return code

    def redeem_code(self, code, client_id, redirect_uri, verifier):
        """One-time PKCE exchange. A replayed code revokes everything it already issued.

        The code is spent in its own committed transaction before the verifier is checked, so a
        failed attempt cannot be retried with a different verifier.
        """
        with self.database.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT * FROM oauth_codes WHERE code_hash=?', (digest(code or ''),)).fetchone()
            spent = bool(row and row['used'])
            if row is not None and not spent:
                db.execute('UPDATE oauth_codes SET used=1 WHERE code_hash=?', (row['code_hash'],))
        if spent:
            self.revoke_grant_id(row['grant_id'])
            raise DomainError('invalid_grant', 400, error_description='authorization code replayed')
        if row is None or row['expires_at'] < time.time():
            raise DomainError('invalid_grant', 400)
        if row['client_id'] != client_id or row['redirect_uri'] != redirect_uri:
            raise DomainError('invalid_grant', 400)
        if not isinstance(verifier, str) or not 43 <= len(verifier) <= 128:
            raise DomainError('invalid_grant', 400, error_description='code_verifier')
        if not hmac.compare_digest(s256(verifier), row['challenge']):
            raise DomainError('invalid_grant', 400, error_description='code_verifier')
        return self.issue_tokens(row['grant_id'], row['user_id'], client_id, row['scope'])

    # --- tokens ------------------------------------------------------------------------

    def issue_tokens(self, grant_id, user_id, client_id, scope):
        access, refresh, now = new_secret(), new_secret(), time.time()
        with self.database.connect() as db:
            db.execute('DELETE FROM oauth_tokens WHERE expires_at<?', (now - 86400,))
            for kind, value, ttl in (('access', access, ACCESS_TTL), ('refresh', refresh, REFRESH_TTL)):
                db.execute('INSERT INTO oauth_tokens VALUES (?,?,?,?,?,?,?,?,0)',
                           (digest(value), kind, grant_id, user_id, client_id, scope, now, now + ttl))
        return dict(access_token=access, refresh_token=refresh, token_type='Bearer',
                    expires_in=ACCESS_TTL, scope=scope)

    def refresh(self, token, client_id):
        """Rotation: the presented refresh token is spent, and reusing a spent one ends the grant."""
        now = time.time()
        with self.database.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute("SELECT * FROM oauth_tokens WHERE token_hash=? AND kind='refresh'",
                             (digest(token or ''),)).fetchone()
            grant = db.execute('SELECT * FROM oauth_grants WHERE grant_id=?',
                               (row['grant_id'],)).fetchone() if row else None
            usable = bool(row and row['client_id'] == client_id and not row['revoked']
                          and row['expires_at'] >= now and grant and not grant['revoked'])
            if usable:
                db.execute('UPDATE oauth_tokens SET revoked=1 WHERE token_hash=?', (row['token_hash'],))
        if usable:
            return self.issue_tokens(row['grant_id'], row['user_id'], client_id, row['scope'])
        if row is not None and row['client_id'] == client_id and (row['revoked'] or row['expires_at'] < now):
            # Someone presented a token this grant already replaced: assume it leaked.
            self.revoke_grant_id(row['grant_id'])
        raise DomainError('invalid_grant', 400)

    def access(self, token):
        """The live principal behind a bearer token, or None. Expiry, revocation and a disabled
        account are all checked here, so a caller never has to."""
        if not isinstance(token, str) or not token:
            return None
        with self.database.connect() as db:
            row = db.execute("SELECT * FROM oauth_tokens WHERE token_hash=? AND kind='access'",
                             (digest(token),)).fetchone()
            if row is None or row['revoked'] or row['expires_at'] < time.time():
                return None
            grant = db.execute('SELECT revoked FROM oauth_grants WHERE grant_id=?', (row['grant_id'],)).fetchone()
        if grant is None or grant['revoked']:
            return None
        user = self.users.active(row['user_id'])
        if user is None:
            return None
        return dict(user_id=row['user_id'], username=user['username'], client_id=row['client_id'],
                    scope=row['scope'].split(), grant_id=row['grant_id'], expires_at=row['expires_at'])

    # --- grants ------------------------------------------------------------------------

    def revoke_token(self, token):
        """RFC 7009: revoking either token of a pair ends the whole grant, and is never an error."""
        with self.database.connect() as db:
            row = db.execute('SELECT grant_id FROM oauth_tokens WHERE token_hash=?',
                             (digest(token or ''),)).fetchone()
        if row:
            self.revoke_grant_id(row['grant_id'])

    def revoke_grant_id(self, grant_id):
        with self.database.connect() as db:
            db.execute('UPDATE oauth_grants SET revoked=1 WHERE grant_id=?', (grant_id,))
            db.execute('UPDATE oauth_tokens SET revoked=1 WHERE grant_id=?', (grant_id,))

    def grants(self, user_id):
        """Connected applications for the account page: one row per live authorization."""
        with self.database.connect() as db:
            rows = db.execute('''SELECT g.grant_id, g.scope, g.created_at, c.name, c.client_id,
                                        MAX(t.expires_at) AS last_expiry
                                 FROM oauth_grants g JOIN oauth_clients c ON c.client_id=g.client_id
                                 LEFT JOIN oauth_tokens t ON t.grant_id=g.grant_id AND t.revoked=0
                                 WHERE g.user_id=? AND g.revoked=0 GROUP BY g.grant_id
                                 ORDER BY g.created_at DESC''', (user_id,)).fetchall()
        return [dict(grant_id=r['grant_id'], client_id=r['client_id'], name=r['name'], scope=r['scope'].split(),
                     created_at=datetime.fromtimestamp(r['created_at'], timezone.utc).isoformat(),
                     active=bool(r['last_expiry'] and r['last_expiry'] > time.time())) for r in rows]

    def revoke_grant(self, user_id, grant_id):
        """Revocation from the account site, which may only end this user's own grants."""
        with self.database.connect() as db:
            changed = db.execute('UPDATE oauth_grants SET revoked=1 WHERE grant_id=? AND user_id=?',
                                 (grant_id, user_id)).rowcount
            db.execute('UPDATE oauth_tokens SET revoked=1 WHERE grant_id=? AND user_id=?', (grant_id, user_id))
        if not changed:
            raise DomainError('grant_not_found', 404)
