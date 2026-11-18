"""What the operator sees: accounts, their activity and the deployment's model spend.

Read-only summaries for the command line (app.py). Nothing here prints user-entered content
such as food text or plan names; it reports identities, timestamps and counts.
"""
from datetime import datetime, timezone

from sqlalchemy import func, select

from .account_data import row_counts, usage_by_account
from .database import transaction
from .models import oauth_grants, oauth_tokens, sessions
from .security import usage_month


def _instant(seconds):
    return datetime.fromtimestamp(seconds, timezone.utc).isoformat() if seconds else None


def _activity(db, user_id):
    session = db.execute(select(func.count(), func.max(sessions.c.created_at))
                         .where(sessions.c.user_id == user_id)).one()
    apps = db.scalar(select(func.count()).select_from(oauth_grants)
                     .where(oauth_grants.c.user_id == user_id, oauth_grants.c.revoked == 0))
    last_token = db.scalar(select(func.max(oauth_tokens.c.created_at)).where(oauth_tokens.c.user_id == user_id))
    # Sessions are removed when they end or expire, so this is the latest live one, not a
    # full sign-in history. Token issue covers the app and MCP, including refreshes.
    return dict(browser_sessions=session[0], last_browser_sign_in_at=_instant(session[1]),
                connected_apps=apps, last_app_token_at=_instant(last_token))


def account_report(accounts, budget):
    month = usage_month(budget.clock())
    usage = usage_by_account(accounts.path, month)
    rows = []
    with transaction(accounts.path) as db:
        for user_id in accounts.users.all_ids():
            user = accounts.users.read(user_id)
            counts = row_counts(db, user_id)
            spend = usage.pop(user_id, {'calls': 0, 'tokens': 0})
            rows.append({**{k: user[k] for k in ('username', 'display_name', 'user_id', 'created_at', 'disabled')},
                         **_activity(db, user_id), 'records': counts['records'],
                         'model_calls_this_month': spend['calls'], 'model_tokens_this_month': spend['tokens']})
    known = {user_id for user_id in accounts.users.all_ids()}
    removed = [v for k, v in usage.items() if k not in known]
    return {'month': month,
            'model_budget': {'monthly_tokens': budget.monthly_tokens, 'spent_tokens': budget.spent(month),
                             'account_monthly_tokens': budget.account_monthly_tokens},
            'accounts': rows,
            'deleted_or_legacy_accounts': {'calls': sum(v['calls'] for v in removed),
                                           'tokens': sum(v['tokens'] for v in removed)}}


def account_details(accounts, budget, username):
    user = accounts.users.by_username(username)
    if user is None:
        raise ValueError(f'No account named {username}')
    month = usage_month(budget.clock())
    with transaction(accounts.path) as db:
        activity, counts = _activity(db, user['user_id']), row_counts(db, user['user_id'])
    return {**user, **activity,
            'apps': [{k: g[k] for k in ('name', 'client_id', 'scope', 'created_at', 'active')}
                     for g in accounts.oauth.grants(user['user_id'])],
            'rows': counts,
            'model_usage_this_month': usage_by_account(accounts.path, month).get(user['user_id'], {'calls': 0, 'tokens': 0})}
