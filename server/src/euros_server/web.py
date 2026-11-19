"""The account website: sign in with a username and password, then manage the account.

This is the only surface where a password is accepted, and it holds no training records of
its own — it reads and writes the signed-in account's own workspace. Each handler decides
three things in the same order: who is asking (browser.py), whether they are allowed to ask
(the session's CSRF token and a same-origin POST), and what to say (account_pages.py,
plan_form.py). Nothing is rendered before the first two are settled.
"""
import hmac
import json

from starlette.concurrency import run_in_threadpool
from starlette.responses import RedirectResponse, Response
from starlette.routing import Route

from .account_data import delete_account, export_account
from .account_pages import (apps_body, data_body, deleted_body, login_body, message_for, overview_body,
                            password_body, signup_body, signup_closed_body)
from .browser import same_origin
from .credentials import password_work
from .domain import DomainError
from .localtime import local_today
from .pages import page
from .plan import generate_sessions
from .plan_form import plan_body, plan_from_form


class AccountSite:
    """Pages for managing one signed-in account."""

    def __init__(self, accounts, workspaces, sessions, allow_signup=True, issuer=None):
        self.accounts, self.workspaces, self.sessions = accounts, workspaces, sessions
        self.allow_signup, self.issuer = allow_signup, issuer

    # --- who is asking, and may they ---------------------------------------------------

    def _require(self, request):
        """The signed-in session, or the redirect that asks them to sign in."""
        session = self.sessions.current(request)
        if session is None:
            return None, RedirectResponse('/account/login', status_code=303)
        return session, None

    def _checked(self, request, form):
        """The session behind an action: signed in, posted from this site, with its CSRF token."""
        session = self.sessions.current(request)
        if session is None:
            raise DomainError('not_signed_in', 401)
        if not same_origin(request, self.issuer) or not hmac.compare_digest(session['csrf'], form.get('csrf', '')):
            raise DomainError('invalid_csrf_token', 403)
        return session

    def _form_page(self, request, title, build, status=200, **fields):
        """A page for a signed-out visitor: minted form token in the body and in a cookie."""
        token, cookie = self.sessions.form_token(request)
        response = page(title, build(token, **fields), status=status)
        self.sessions.attach_form_cookie(request, response, cookie)
        return response

    def _signed_in(self, request, user_id):
        """Start a session for this browser and send it to the account."""
        response = RedirectResponse('/account/', status_code=303)
        self.sessions.start(request, response, user_id)
        self.sessions.clear_form_cookie(response)
        return response

    # --- sign in and sign up -----------------------------------------------------------
    #
    # Pages without a form are plain functions, which Starlette runs on a request thread.
    # Form handlers parse the form on the event loop and pass the rest to a thread
    # (_threaded). Password checks go through password_work, which has threads of its own.

    @staticmethod
    async def _form(request):
        return dict(await request.form())

    def _login_page(self, request):
        if self.sessions.current(request):
            return RedirectResponse('/account/', status_code=303)
        return self._form_page(request, 'Sign in', login_body, allow_signup=self.allow_signup)

    async def login(self, request):
        if request.method == 'GET':
            return await run_in_threadpool(self._login_page, request)
        form = await self._form(request)
        if not same_origin(request, self.issuer) or not self.sessions.valid_form(request, form.get('form_token')):
            return self._form_page(request, 'Sign in', login_body, status=400,
                                   message='That form expired. Try again.', allow_signup=self.allow_signup)
        try:
            user = await password_work(self.accounts.users.authenticate, form.get('username', ''),
                                        form.get('password', ''))
        except DomainError as exc:
            # Sign-in says the same thing for a wrong password and an unknown username.
            message = ('Wrong username or password.' if exc.body['error'] == 'invalid_credentials'
                       else message_for(exc))
            return self._form_page(request, 'Sign in', login_body, status=exc.status, message=message,
                                   username=str(form.get('username', ''))[:32], allow_signup=self.allow_signup)
        return await run_in_threadpool(self._signed_in, request, user['user_id'])

    async def signup(self, request):
        if not self.allow_signup:
            return page('Create your account', signup_closed_body(), status=403)
        if request.method == 'GET':
            return self._form_page(request, 'Create your account', signup_body)
        form = await self._form(request)
        if not same_origin(request, self.issuer) or not self.sessions.valid_form(request, form.get('form_token')):
            return self._form_page(request, 'Create your account', signup_body, status=400,
                                   message='That form expired. Try again.')
        values = {k: str(form.get(k, ''))[:80] for k in ('username', 'display_name')}
        if form.get('password') != form.get('password2'):
            return self._form_page(request, 'Create your account', signup_body, status=422,
                                   message='The two passwords do not match.', values=values)
        try:
            user = await password_work(self.accounts.users.create, form.get('username', ''),
                                        form.get('password', ''), form.get('display_name') or None)
        except DomainError as exc:
            return self._form_page(request, 'Create your account', signup_body, status=exc.status,
                                   message=message_for(exc), values=values)
        return await run_in_threadpool(self._new_account, request, user['user_id'])

    def _new_account(self, request, user_id):
        # Initialize this account's services in the shared database.
        self.workspaces.get(user_id)
        return self._signed_in(request, user_id)

    async def logout(self, request):
        return await run_in_threadpool(self._logout, request, await self._form(request))

    def _logout(self, request, form):
        destination = form.get('next', '/account/login')
        if not local_path(destination):
            destination = '/account/login'
        response = RedirectResponse(destination, status_code=303)
        session = self.sessions.current(request)
        if session and (not same_origin(request, self.issuer)
                        or not hmac.compare_digest(session['csrf'], form.get('csrf', ''))):
            raise DomainError('invalid_csrf_token', 403)
        self.sessions.end(request, response)
        return response

    # --- overview ----------------------------------------------------------------------

    def overview(self, request):
        session, redirect = self._require(request)
        if redirect:
            return redirect
        user = session['user']
        record = self.workspaces.get(user['user_id']).plans.read()
        return page('Your account', overview_body(user, record), user=user)

    # --- training plan -----------------------------------------------------------------

    def _plan_page(self, request, user, message=None, kind='notice', status=200):
        record = self.workspaces.get(user['user_id']).plans.read()
        body = plan_body(record, self.sessions.csrf(request) or '', local_today(), message, kind)
        return page('Training plan', body, user=user, status=status)

    def plan_page(self, request):
        session, redirect = self._require(request)
        if redirect:
            return redirect
        return self._plan_page(request, session['user'])

    def _save_plan(self, request, form):
        user = self._checked(request, form)['user']
        try:
            payload = plan_from_form(form)
            revision = int(form.get('revision', 0))
        except (ValueError, TypeError):
            return self._plan_page(request, user, 'That form could not be read. Try again.', 'error', 400)
        except DomainError as exc:
            return self._plan_page(request, user, message_for(exc), 'error', exc.status)
        try:
            self.workspaces.get(user['user_id']).plans.write(payload, revision)
        except DomainError as exc:
            return self._plan_page(request, user, message_for(exc), 'error', exc.status)
        return self._plan_page(request, user, 'Training plan saved.')

    def _generate(self, request, form):
        user = self._checked(request, form)['user']
        workspace = self.workspaces.get(user['user_id'])
        try:
            result = generate_sessions(workspace.store, workspace.plans.plan(),
                                       str(form.get('date_from', '')), str(form.get('date_to', '')))
        except DomainError as exc:
            return self._plan_page(request, user, message_for(exc), 'error', exc.status)
        return self._plan_page(request, user,
                               f'Created {len(result["created"])} sessions; '
                               f'{len(result["skipped"])} dates already had one.')

    # --- password ----------------------------------------------------------------------

    def _password_page(self, request, user, message=None, kind='notice', status=200):
        return page('Password', password_body(self.sessions.csrf(request) or '', message, kind),
                    user=user, status=status)

    def password_page(self, request):
        session, redirect = self._require(request)
        if redirect:
            return redirect
        return self._password_page(request, session['user'])

    async def change_password(self, request):
        form = await self._form(request)
        user = (await run_in_threadpool(self._checked, request, form))['user']
        if form.get('password') != form.get('password2'):
            return await run_in_threadpool(self._password_page, request, user,
                                           'The two new passwords do not match.', 'error', 422)
        try:
            await password_work(self.accounts.users.set_password, user['user_id'], form.get('current', ''),
                                form.get('password', ''))
        except DomainError as exc:
            return await run_in_threadpool(self._password_page, request, user, message_for(exc), 'error',
                                           exc.status)
        return await run_in_threadpool(self._password_changed, request, user)

    def _password_changed(self, request, user):
        self.accounts.sessions.end_all(user['user_id'], keep=self.sessions.cookie(request))
        return self._password_page(request, user, 'Password changed. Other browsers were signed out.')

    # --- connected apps ----------------------------------------------------------------

    def _apps_page(self, request, user, message=None):
        grants = self.accounts.oauth.grants(user['user_id'])
        return page('Connected apps', apps_body(self.sessions.csrf(request) or '', grants, message), user=user)

    def apps_page(self, request):
        session, redirect = self._require(request)
        if redirect:
            return redirect
        return self._apps_page(request, session['user'])

    def _revoke_app(self, request, form):
        user = self._checked(request, form)['user']
        try:
            self.accounts.oauth.revoke_grant(user['user_id'], str(form.get('grant_id', '')))
        except DomainError:
            return self._apps_page(request, user, 'That application was already disconnected.')
        return self._apps_page(request, user, 'Application disconnected.')

    # --- your data: export and deletion -------------------------------------------------

    def _data_page(self, request, user, message=None, kind='notice', status=200):
        body = data_body(self.sessions.csrf(request) or '', user['username'], message, kind)
        return page('Your data', body, user=user, status=status)

    def data_page(self, request):
        session, redirect = self._require(request)
        if redirect:
            return redirect
        return self._data_page(request, session['user'])

    def export(self, request):
        session, redirect = self._require(request)
        if redirect:
            return redirect
        user = session['user']
        snapshot = export_account(self.accounts.path, user['user_id'])
        name = f'euros-{user["username"]}-{snapshot["exported_at"][:10]}.json'.replace('@', '_at_')
        return Response(json.dumps(snapshot, indent=1, sort_keys=True), media_type='application/json',
                        headers={'Content-Disposition': f'attachment; filename="{name}"',
                                 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'})

    async def delete(self, request):
        form = await self._form(request)
        user = (await run_in_threadpool(self._checked, request, form))['user']
        if str(form.get('confirm', '')).strip().lower() != user['username']:
            return await run_in_threadpool(self._data_page, request, user,
                                           message_for(DomainError('confirmation_mismatch')), 'error', 422)
        try:
            # The sign-in check, with its throttling, so a left-open browser cannot be used
            # to guess the password here either.
            await password_work(self.accounts.users.authenticate, user['username'], form.get('password', ''))
        except DomainError as exc:
            return await run_in_threadpool(self._data_page, request, user, message_for(exc), 'error', exc.status)
        return await run_in_threadpool(self._deleted, request, user)

    def _deleted(self, request, user):
        delete_account(self.accounts.path, user['user_id'])
        self.workspaces.forget(user['user_id'])
        response = page('Account deleted', deleted_body())
        self.sessions.end(request, response)
        return response

    def _threaded(self, handler):
        """An endpoint that parses its form on the event loop and runs `handler` on a thread."""
        async def endpoint(request):
            return await run_in_threadpool(handler, request, await self._form(request))
        return endpoint

    def routes(self):
        return [Route('/account/', self.overview),
                Route('/account/login', self.login, methods=['GET', 'POST']),
                Route('/account/signup', self.signup, methods=['GET', 'POST']),
                Route('/account/logout', self.logout, methods=['POST']),
                Route('/account/plan', self.plan_page, methods=['GET']),
                Route('/account/plan', self._threaded(self._save_plan), methods=['POST']),
                Route('/account/plan/sessions', self._threaded(self._generate), methods=['POST']),
                Route('/account/password', self.password_page, methods=['GET']),
                Route('/account/password', self.change_password, methods=['POST']),
                Route('/account/apps', self.apps_page),
                Route('/account/apps/revoke', self._threaded(self._revoke_app), methods=['POST']),
                Route('/account/data', self.data_page),
                Route('/account/export', self.export),
                Route('/account/delete', self.delete, methods=['POST'])]


def local_path(destination):
    """A path on this site. "//host/..." and "/\\host" are other sites to a browser."""
    return (isinstance(destination, str) and destination.startswith('/')
            and not destination.startswith(('//', '/\\')) and '\\' not in destination
            and not any(ord(c) < 32 for c in destination))
