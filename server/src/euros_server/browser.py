"""What a browser presents and what it is given back: cookies and the origin of a request.

The account site's defence against another site posting on a user's behalf lives here: a
session cookie that is HttpOnly, same-site and Secure wherever the browser is not on
loopback, a one-use token for the forms that have no session yet, and the check that a POST
came from this site. Which account a cookie names is decided in sessions.py.
"""
import hmac
import secrets
from urllib.parse import urlsplit

SESSION_COOKIE = 'euros_session'
FORM_COOKIE = 'euros_form'
SESSION_MAX_AGE = 14 * 86400
FORM_MAX_AGE = 900


def base_url(request, configured=None):
    """This service's own public origin.

    Behind a TLS terminator the request arrives over http on a private socket, so a configured
    public URL wins and the proxy's forwarded scheme is used otherwise. Both are deployment
    configuration, never values a browser can choose.
    """
    if configured:
        return configured.rstrip('/')
    scheme = request.headers.get('x-forwarded-proto', request.url.scheme).split(',')[0].strip()
    return f'{scheme}://{request.headers.get("host", request.url.netloc)}'


def same_origin(request, configured=None):
    """A browser POST must come from this site; a request without an Origin states no site."""
    mine = {base_url(request, configured), base_url(request)}
    origin = request.headers.get('origin')
    if origin is None:
        referer = request.headers.get('referer')
        if referer is None:
            return True
        parts = urlsplit(referer)
        return f'{parts.scheme}://{parts.netloc}' in mine
    return origin in mine


class BrowserSessions:
    """The cookie side of a session: what this browser holds, and the one-use token that
    protects the sign-in forms. The stored session itself lives in sessions.py."""

    def __init__(self, accounts, issuer=None):
        self.accounts, self.issuer = accounts, issuer

    def _secure(self, request):
        # Loopback development runs over http; anything else must not leak a session cookie.
        return base_url(request, self.issuer).startswith('https://')

    def current(self, request):
        session = self.accounts.sessions.read(request.cookies.get(SESSION_COOKIE, ''))
        if session:
            # For the request log only: which account this page or form belonged to.
            request.scope['euros_session_user'] = session['user']['user_id']
        return session

    def csrf(self, request):
        session = self.current(request)
        return session['csrf'] if session else None

    def cookie(self, request):
        return request.cookies.get(SESSION_COOKIE, '')

    def start(self, request, response, user_id):
        value, _ = self.accounts.sessions.start(user_id)
        response.set_cookie(SESSION_COOKIE, value, httponly=True, samesite='lax',
                            secure=self._secure(request), max_age=SESSION_MAX_AGE, path='/')
        return value

    def end(self, request, response):
        self.accounts.sessions.end(self.cookie(request))
        response.delete_cookie(SESSION_COOKIE, path='/')

    # --- one-use form token, for the forms a signed-out visitor posts -------------------

    def form_token(self, request):
        """A value to put in the form and in a cookie; a submission must present both."""
        value = secrets.token_urlsafe(24)
        return value, value

    def attach_form_cookie(self, request, response, value):
        response.set_cookie(FORM_COOKIE, value, httponly=True, samesite='lax',
                            secure=self._secure(request), max_age=FORM_MAX_AGE, path='/')

    def clear_form_cookie(self, response):
        response.delete_cookie(FORM_COOKIE, path='/')

    def valid_form(self, request, submitted):
        cookie = request.cookies.get(FORM_COOKIE, '')
        return bool(cookie) and isinstance(submitted, str) and hmac.compare_digest(cookie, submitted)
