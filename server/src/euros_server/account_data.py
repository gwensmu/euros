"""Everything one account holds in the shared database: export it, count it, or delete it.

These are the only operations that touch every table at once, so each one names the account
in every statement itself rather than relying on an account session. OWNED_TABLES is the list
of account-owned training tables; the authentication tables are listed here by hand.

Deletion is immediate and permanent. It removes the account's training data, its sign-in
state and the account row in one transaction. Two things deliberately remain: OAuth client
registrations (shared, not owned by an account) and the deployment's model_usage ledger,
whose rows hold only token counts so that deleting an account never refunds the budget.
"""
import json
from datetime import datetime, timezone

from sqlalchemy import delete, func, select

from .database import transaction
from .models import (OWNED_TABLES, User, imported_databases, login_failures, model_usage,
                     oauth_codes, oauth_grants, oauth_tokens, retired_account_data, sessions)

EXPORT_VERSION = 1
# Columns stored as canonical JSON text; exported as structured values.
JSON_COLUMNS = {'payload', 'record', 'result', 'body', 'row_json'}
# Rate-limit bookkeeping: timestamps of model calls, nothing the person wrote or saved.
NOT_EXPORTED = {'inference_requests'}
# Child tables before their parents, so foreign keys hold at every step.
AUTH_TABLES = (oauth_tokens, oauth_codes, oauth_grants, sessions, imported_databases, retired_account_data)

users = User.__table__


def _value(column, value):
    if column in JSON_COLUMNS and isinstance(value, str):
        try:
            return json.loads(value)
        except ValueError:
            return value
    return value


def _owned_rows(db, table, user_id):
    rows = db.execute(select(table).where(table.c.user_id == user_id)
                      .order_by(*table.primary_key.columns)).mappings().all()
    return [{k: _value(k, v) for k, v in row.items() if k != 'user_id'} for row in rows]


def export_account(path, user_id):
    """A portable JSON-ready snapshot of one account: profile, plan and every training table.

    Never includes the password verifier, session or token digests, or another account's rows.
    This is a copy for the user to keep; it is not the phone's restore format.
    """
    with transaction(path) as db:
        user = db.execute(select(users).where(users.c.user_id == user_id)).mappings().first()
        if user is None:
            raise LookupError(user_id)
        tables = {name: _owned_rows(db, table, user_id)
                  for name, table in sorted(OWNED_TABLES.items()) if name not in NOT_EXPORTED}
        apps = db.execute(select(oauth_grants.c.client_id, oauth_grants.c.scope, oauth_grants.c.created_at)
                          .where(oauth_grants.c.user_id == user_id, oauth_grants.c.revoked == 0)).mappings().all()
    return {'format': 'euros-account-export', 'version': EXPORT_VERSION,
            'exported_at': datetime.now(timezone.utc).isoformat(),
            'account': {k: user[k] for k in ('username', 'display_name', 'created_at', 'password_changed_at')},
            'connected_apps': [{'client_id': a['client_id'], 'scope': a['scope'].split(),
                                'connected_at': datetime.fromtimestamp(a['created_at'], timezone.utc).isoformat()}
                               for a in apps],
            'tables': tables}


def row_counts(db, user_id):
    """Rows per account-owned table, for the operator view."""
    return {name: db.scalar(select(func.count()).select_from(table).where(table.c.user_id == user_id))
            for name, table in sorted(OWNED_TABLES.items())}


def delete_account(path, user_id):
    """Remove the account and everything it owns, in one transaction. Returns rows removed."""
    with transaction(path, write=True) as db:
        user = db.execute(select(users).where(users.c.user_id == user_id)).mappings().first()
        if user is None:
            raise LookupError(user_id)
        removed = {}
        for table in AUTH_TABLES:
            removed[table.name] = db.execute(delete(table).where(table.c.user_id == user_id)).rowcount
        for name, table in sorted(OWNED_TABLES.items()):
            removed[name] = db.execute(delete(table).where(table.c.user_id == user_id)).rowcount
        removed[login_failures.name] = db.execute(
            delete(login_failures).where(login_failures.c.username == user['username'])).rowcount
        db.execute(delete(users).where(users.c.user_id == user_id))
    return {'username': user['username'], 'user_id': user_id,
            'rows': {k: v for k, v in removed.items() if v}}


def usage_by_account(path, month):
    """Tokens and calls per account id for one UTC month, including deleted accounts' ids."""
    with transaction(path) as db:
        rows = db.execute(select(model_usage.c.user_id, func.count().label('calls'),
                                 func.coalesce(func.sum(model_usage.c.tokens), 0).label('tokens'))
                          .where(model_usage.c.month == month).group_by(model_usage.c.user_id)).mappings().all()
    return {r['user_id']: {'calls': r['calls'], 'tokens': int(r['tokens'])} for r in rows}
