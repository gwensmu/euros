"""The account website: signing in with a password and managing the account."""
import re

import pytest
from starlette.testclient import TestClient

from conftest import PASSWORD, SilentProvider
from euros_server.accounts import AccountStore
from euros_server.users import MAX_FAILURES
from euros_server.app import create_service
from euros_server.llm import ModelService


def test_signup_signs_in_and_shows_the_plan(service):
    created, _ = service.signup('athlete', display_name='Sam')
    assert created.status_code == 303 and created.headers['location'] == '/account/'
    assert service.client.cookies.get('euros_session')
    overview = service.client.get('/account/')
    assert 'Euros' in overview.text and 'Sam' in overview.text
    assert 'defaults — not set yet' in overview.text
    assert service.accounts.users.by_username('athlete')


def test_session_cookie_is_http_only_and_lax(service):
    created, _ = service.signup('athlete')
    cookie = created.headers['set-cookie']
    assert 'HttpOnly' in cookie and 'SameSite=lax' in cookie.replace('Lax', 'lax')
    # Loopback development runs over http, so Secure is only set off-loopback.
    assert 'Secure' not in cookie


def test_pages_require_a_session(service):
    for path in ('/account/', '/account/plan', '/account/apps', '/account/password'):
        response = service.client.get(path, follow_redirects=False)
        assert response.status_code == 303 and response.headers['location'] == '/account/login'


def test_sign_in_rejects_a_wrong_password_and_then_throttles(service):
    service.signup('athlete')
    service.logout()
    for _ in range(MAX_FAILURES):
        failed = service.login('athlete', 'not the password')
        assert failed.status_code == 401 and 'Wrong username or password' in failed.text
    throttled = service.login('athlete', PASSWORD)
    assert throttled.status_code == 429 and 'Too many sign-in attempts' in throttled.text
    assert not service.client.cookies.get('euros_session')


def test_saving_the_plan_changes_the_targets_the_api_reports(service):
    service.signup('athlete')
    plan = service.client.get('/account/plan')
    assert 'Training plan' in plan.text
    form = _plan_form(service.csrf('/account/plan'))
    form.update({'competition.name': 'Pan Ams', 'competition.date': '2027-03-04',
                 'tier.recovery.kcal': '1700', 'goal.weight_lb': '149'})
    saved = service.client.post('/account/plan', data=form)
    assert saved.status_code == 200 and 'Training plan saved' in saved.text
    assert 'Pan Ams' in service.client.get('/account/').text
    service.logout()
    _, tokens = service.connect('athlete')
    read = service.api(tokens['access_token'])('GET', '/v1/plan').json()
    assert read['source'] == 'user' and read['plan']['competition'] == {'name': 'Pan Ams', 'date': '2027-03-04'}
    assert read['plan']['goal']['weight_lb'] == 149
    totals = service.api(tokens['access_token'])('GET', '/v1/nutrition?local_date=2026-12-01').json()
    assert totals['targets']['kcal'] == 1700


def test_plan_form_reports_its_own_mistakes(service):
    service.signup('athlete')
    form = _plan_form(service.csrf('/account/plan'))
    form['tier.bjj.kcal'] = 'lots'
    answer = service.client.post('/account/plan', data=form)
    assert answer.status_code == 422 and 'Enter calorie and macro amounts as numbers' in answer.text
    assert service.client.get('/account/').text.count('defaults — not set yet') == 1


def test_a_form_without_the_session_token_is_refused(service):
    service.signup('athlete')
    form = _plan_form('not-the-token')
    assert service.client.post('/account/plan', data=form).status_code == 403
    form = _plan_form(service.csrf('/account/plan'))
    cross_site = service.client.post('/account/plan', data=form, headers={'Origin': 'https://evil.example'})
    assert cross_site.status_code == 403


def test_generated_sessions_appear_as_activities_and_do_not_duplicate(service):
    service.signup('athlete')
    csrf = service.csrf('/account/plan')
    first = service.client.post('/account/plan/sessions',
                                data={'csrf': csrf, 'date_from': '2026-12-01', 'date_to': '2026-12-14'})
    assert 'Created 8 sessions' in first.text
    again = service.client.post('/account/plan/sessions',
                                data={'csrf': csrf, 'date_from': '2026-12-01', 'date_to': '2026-12-14'})
    assert 'Created 0 sessions; 8 dates already had one' in again.text
    service.logout()
    _, tokens = service.connect('athlete')
    records = service.api(tokens['access_token'])('GET', '/v1/records?entity_type=activities').json()['records']
    assert len(records) == 8 and all(r['payload']['source'] == 'training_plan' for r in records)


def test_changing_the_password_signs_other_browsers_out(service):
    service.signup('athlete')
    kept = service.client.cookies.get('euros_session')
    elsewhere, _ = service.accounts.sessions.start(service.accounts.users.by_username('athlete')['user_id'])
    changed = service.client.post('/account/password', data={
        'csrf': service.csrf('/account/password'), 'current': PASSWORD,
        'password': 'another long password', 'password2': 'another long password'})
    assert changed.status_code == 200 and 'Password changed' in changed.text
    assert service.accounts.sessions.read(elsewhere) is None and service.accounts.sessions.read(kept)
    service.logout()
    assert service.login('athlete', 'another long password').status_code == 303


def test_password_change_needs_the_current_password(service):
    service.signup('athlete')
    refused = service.client.post('/account/password', data={
        'csrf': service.csrf('/account/password'), 'current': 'wrong password here',
        'password': 'another long password', 'password2': 'another long password'})
    assert refused.status_code == 401 and 'not correct' in refused.text
    mismatch = service.client.post('/account/password', data={
        'csrf': service.csrf('/account/password'), 'current': PASSWORD,
        'password': 'another long password', 'password2': 'different long password'})
    assert mismatch.status_code == 422 and 'do not match' in mismatch.text


def test_connected_apps_can_be_revoked_from_the_site(service):
    service.signup('athlete')
    service.logout()
    _, tokens = service.connect('athlete', name='Claude')
    call = service.api(tokens['access_token'])
    assert call('GET', '/v1/records').status_code == 200
    service.login('athlete')
    apps = service.client.get('/account/apps')
    assert 'Claude' in apps.text
    grant_id = re.search('name="grant_id" value="([^"]+)"', apps.text).group(1)
    revoked = service.client.post('/account/apps/revoke',
                                  data={'csrf': service.csrf('/account/apps'), 'grant_id': grant_id})
    assert 'Application disconnected' in revoked.text
    assert call('GET', '/v1/records').status_code == 401


def test_signup_is_closed_unless_the_deployment_opens_it(tmp_path):
    accounts = AccountStore(tmp_path / 'accounts.db', iterations=1000)
    app = create_service(tmp_path, model_service=ModelService(SilentProvider()), accounts=accounts)
    with TestClient(app, base_url='http://localhost') as client:
        assert client.get('/account/signup').status_code == 403
        assert client.post('/account/signup', data={'username': 'athlete', 'password': PASSWORD,
                                                    'password2': PASSWORD}).status_code == 403
        assert accounts.users.count() == 0


def _plan_form(csrf, revision=0):
    """The plan page's defaults as a browser would post them back."""
    form = {'csrf': csrf, 'revision': str(revision), 'competition.name': 'Euros',
            'competition.date': '2027-01-13', 'goal.weight_lb': '155', 'goal.maintenance': 'on',
            'maintenance.kcal': '200', 'maintenance.carbohydrateG': '50'}
    tiers = {'recovery': (1850, 120, 165, 72), 'lifting': (2050, 120, 220, 70),
             'bjj': (2300, 120, 305, 60), 'double': (2550, 120, 370, 59)}
    for name, values in tiers.items():
        for macro, value in zip(('kcal', 'proteinG', 'carbohydrateG', 'fatG'), values):
            form[f'tier.{name}.{macro}'] = str(value)
    for slot, clock in (('breakfast', '08:00'), ('lunch', '12:30'), ('dinner', '18:30'),
                        ('recovery-snack', '20:30')):
        form[f'meal.{slot}.on'] = 'on'
        form[f'meal.{slot}.time'] = clock
        form[f'meal.{slot}.title'] = slot.replace('-', ' ').title()
    for index, (weekday, clock, kind) in enumerate([('tuesday', '18:30', 'bjj'), ('thursday', '18:30', 'bjj'),
                                                    ('saturday', '10:00', 'bjj'), ('sunday', '11:00', 'weights')]):
        form[f'session.{index}.weekday'] = weekday
        form[f'session.{index}.time'] = clock
        form[f'session.{index}.kind'] = kind
        form[f'session.{index}.title'] = 'Training'
    return form


def test_behind_a_tls_terminator_the_public_url_decides_origin_and_cookies(tmp_path):
    """The app is reached over http on a private socket; the browser is on https://euros.example."""
    accounts = AccountStore(tmp_path / 'accounts.db', iterations=1000)
    app = create_service(tmp_path, ['euros.example'], model_service=ModelService(SilentProvider()),
                         accounts=accounts, allow_signup=True, issuer='https://euros.example')
    jar = {}

    def browser(method, path, **kwargs):
        headers = {'Host': 'euros.example', 'X-Forwarded-Proto': 'https',
                   'Origin': kwargs.pop('origin', 'https://euros.example'),
                   **({'Cookie': '; '.join(f'{k}={v}' for k, v in jar.items())} if jar else {})}
        response = client.request(method, path, headers=headers, follow_redirects=False, **kwargs)
        for raw in response.headers.get_list('set-cookie'):
            name, _, rest = raw.partition('=')
            jar[name] = rest.split(';')[0]
            if jar[name] not in ('', '""'):
                assert 'Secure' in raw, 'a session cookie must not be sent in the clear'
        return response

    with TestClient(app, base_url='http://euros.example') as client:
        metadata = browser('GET', '/.well-known/oauth-authorization-server').json()
        assert metadata['issuer'] == 'https://euros.example'
        challenge = browser('GET', '/v1/records').headers['www-authenticate']
        assert 'https://euros.example/.well-known/oauth-protected-resource' in challenge
        browser('GET', '/account/signup')
        created = browser('POST', '/account/signup', data={
            'form_token': jar['euros_form'], 'username': 'athlete',
            'password': PASSWORD, 'password2': PASSWORD})
        assert created.status_code == 303, created.text
        csrf = re.search('name="csrf" value="([^"]+)"', browser('GET', '/account/plan').text).group(1)
        generated = browser('POST', '/account/plan/sessions',
                            data={'csrf': csrf, 'date_from': '2026-12-01', 'date_to': '2026-12-07'})
        assert generated.status_code == 200 and 'Created 4 sessions' in generated.text
        elsewhere = browser('POST', '/account/plan/sessions', origin='https://evil.example',
                            data={'csrf': csrf, 'date_from': '2026-12-08', 'date_to': '2026-12-14'})
        assert elsewhere.status_code == 403


def test_the_root_address_points_at_the_account_site(service):
    signpost = service.client.get('/', follow_redirects=False)
    assert signpost.status_code == 303 and signpost.headers['location'] == '/account/'
    # Everything else still needs a token, including the MCP endpoint behind the same mount.
    assert service.client.get('/nope').status_code == 401
    assert service.client.post('/mcp', json={}).status_code == 401
