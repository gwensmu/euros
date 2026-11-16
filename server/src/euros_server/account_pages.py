"""What the account site says: the body of each page, and the sentence an error becomes.

Pure rendering. Every function takes values already decided by a handler and returns escaped
HTML; nothing here reads a request, a cookie or the database, and nothing here decides
whether an action was allowed. The page chrome is in pages.py and the plan form, which also
has to parse itself back, is in plan_form.py.
"""
from datetime import date
from html import escape

from .credentials import MIN_PASSWORD
from .localtime import local_today
from .pages import field, hidden, notice
from .plan import TIER_NAMES
from .plan_form import TIER_LABELS, macro_text


def login_body(form_token, message=None, username='', allow_signup=True):
    signup = ('<p class="muted">No account yet? <a href="/account/signup">Create one</a>.</p>'
              if allow_signup else '')
    return (f'{notice(message, "error") if message else ""}'
            f'<form method="post" action="/account/login">{hidden("form_token", form_token)}'
            f'{field("Username or email", "username", username)}'
            f'{field("Password", "password", kind="password")}'
            f'<button>Sign in</button></form>{signup}')


def signup_body(form_token, message=None, values=None):
    values = values or {}
    return (f'{notice(message, "error") if message else ""}'
            f'<form method="post" action="/account/signup">{hidden("form_token", form_token)}'
            f'{field("Username or email", "username", values.get("username", ""))}'
            f'{field("Display name (optional)", "display_name", values.get("display_name", ""))}'
            f'{field("Password", "password", kind="password")}'
            f'{field("Repeat password", "password2", kind="password")}'
            f'<p class="muted">At least {MIN_PASSWORD} characters.</p>'
            f'<button>Create account</button></form>'
            f'<p class="muted">Already have an account? <a href="/account/login">Sign in</a>.</p>')


def signup_closed_body():
    return notice('This server is not accepting new accounts.', 'error')


def overview_body(user, record, today=None):
    """The account at a glance: how long until the event, and the plan behind that answer."""
    plan = record['plan']
    event = date.fromisoformat(plan['competition']['date'])
    days = (event - (today or local_today())).days
    name = escape(plan['competition']['name'])
    countdown = (f'{days} days to {name}' if days > 0 else
                 f'{name} is today' if days == 0 else f'{name} was {-days} days ago')
    tiers = ''.join(f'<tr><td>{escape(TIER_LABELS[tier])}</td>'
                    f'<td>{escape(str(macro_text(plan["calorie_goals"]["tiers"][tier]["kcal"])))} kcal</td>'
                    f'<td>{escape(str(macro_text(plan["calorie_goals"]["tiers"][tier]["proteinG"])))} g protein</td></tr>'
                    for tier in TIER_NAMES)
    meals = ''.join(f'<li>{escape(m["local_time"])} · {escape(m["title"])}</li>' for m in plan['meals'])
    sessions = ''.join(f'<li>{escape(s["weekday"].title())} {escape(s["local_time"])} · '
                       f'{escape(s["title"])} ({escape(s["kind"])})</li>' for s in plan['workout_schedule'])
    return (f'<p class="notice">{countdown}</p>'
            f'<p class="muted">Signed in as {escape(user["username"])}. '
            f'Training plan {"you set" if record["source"] == "user" else "defaults — not set yet"}.</p>'
            f'<h2>Calorie goals</h2><table>{tiers}</table>'
            f'<h2>Goal weight</h2><p>{escape(str(plan["goal"]["weight_lb"]))} lb · maintenance '
            f'{"on" if plan["goal"]["maintenance"] else "off"}</p>'
            f'<h2>Meals</h2><ul>{meals or "<li>None set</li>"}</ul>'
            f'<h2>Weekly workouts</h2><ul>{sessions or "<li>None set</li>"}</ul>'
            f'<p><a href="/account/plan">Edit training plan</a></p>')


def password_body(csrf, message=None, kind='notice'):
    return (f'{notice(message, kind)}'
            f'<form method="post" action="/account/password">{hidden("csrf", csrf)}'
            f'{field("Current password", "current", kind="password")}'
            f'{field("New password", "password", kind="password")}'
            f'{field("Repeat new password", "password2", kind="password")}'
            f'<p class="muted">At least {MIN_PASSWORD} characters. Changing your password signs out '
            f'your other browsers; connected apps keep working until you revoke them.</p>'
            f'<button>Change password</button></form>')


def apps_body(csrf, grants, message=None):
    rows = ''.join(
        f'<tr><td>{escape(g["name"])}</td><td class="muted">{escape(", ".join(g["scope"]))}</td>'
        f'<td class="muted">{escape(g["created_at"][:10])}</td>'
        f'<td><form class="inline" method="post" action="/account/apps/revoke">{hidden("csrf", csrf)}'
        f'{hidden("grant_id", g["grant_id"])}<button class="quiet">Revoke</button></form></td></tr>'
        for g in grants)
    return (f'{notice(message)}'
            f'<p class="muted">Applications you have allowed to use this account, including the '
            f'Euros phone app and any MCP client. Revoking takes effect immediately.</p>'
            f'<table><tr><th>Application</th><th>Access</th><th>Since</th><th></th></tr>'
            f'{rows or "<tr><td>None yet</td><td></td><td></td><td></td></tr>"}</table>')


def message_for(exc):
    """A short sentence for a page; never the raw error body."""
    return {
        'username_taken': 'That username is already in use.',
        'invalid_username': 'Use a 3-32 character username or an email address.',
        'invalid_password': f'Passwords are {MIN_PASSWORD}-200 characters.',
        'invalid_credentials': 'That password is not correct.',
        'too_many_attempts': 'Too many sign-in attempts. Try again later.',
        'revision_conflict': 'Your plan changed in another window. Reload and try again.',
        'invalid_competition_date': 'Enter a valid competition date.',
        'invalid_plan_date_range': 'Choose a date range of at most 180 days, starting on or before the end date.',
        'duplicate_meal_slot': 'Each meal can only appear once.',
        'duplicate_session_time': 'Two sessions cannot start at the same time on the same day.',
        'invalid_plan_time': 'Enter times as HH:MM.',
        'invalid_plan_quantity': 'Enter calorie and macro amounts as numbers, or a range like 50-53.',
        'invalid_quantity': 'Enter amounts as positive numbers.',
        'invalid_goal': 'Enter a goal weight between 50 and 600 lb.',
    }.get(exc.body.get('error'), 'That change could not be saved.')
