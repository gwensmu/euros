"""Real PostgreSQL tests. Set EUROS_TEST_DATABASE_URL to a disposable test server."""
import os
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest
from sqlalchemy import select, update
from sqlalchemy.engine import make_url
from starlette.testclient import TestClient
from euros_server.accounts import AccountStore
from euros_server.app import create_service
from euros_server.database import engine, LEGACY_OWNER
from euros_server.domain import Store
from euros_server.llm import ModelService
from euros_server.models import User
from euros_server.postgres_migration import import_snapshot
from conftest import PASSWORD, Service, SilentProvider
from test_isolation import activity, two_accounts
from test_storage_migration import legacy


@pytest.fixture
def pg():
    raw = os.environ.get('EUROS_TEST_DATABASE_URL')
    if not raw:
        pytest.skip('PostgreSQL integration server not configured')
    schema = 'test_' + uuid.uuid4().hex
    base = engine(raw)
    with base.begin() as db:
        db.exec_driver_sql('CREATE SCHEMA ' + schema)
    url = make_url(raw).update_query_dict({'options':'-csearch_path=' + schema}).render_as_string(hide_password=False)
    try:
        yield url
    finally:
        engine(url).dispose()
        with base.begin() as db:
            db.exec_driver_sql('DROP SCHEMA ' + schema + ' CASCADE')


def test_postgres_api_accounts_oauth_and_cache_isolation(pg):
    from test_isolation import test_records_stay_inside_the_account_that_wrote_them, test_mcp_tools_read_the_token_holders_records
    accounts = AccountStore(pg, iterations=1000)
    with TestClient(create_service(pg, accounts=accounts, model_service=ModelService(SilentProvider()), allow_signup=True), base_url='http://localhost') as client:
        service = Service(client, accounts, pg)
        test_records_stay_inside_the_account_that_wrote_them(service)
        # Use different names for a second independent API/MCP flow.
        with accounts.transaction(write=True) as db:
            db.execute(update(User).values(username=User.username + '-prior'))
        test_mcp_tools_read_the_token_holders_records(service)


def test_postgres_concurrent_commands_are_atomic_and_account_scoped(pg):
    accounts = AccountStore(pg, iterations=1000)
    stores = [Store(pg, accounts.users.create(n,PASSWORD)['user_id']) for n in ('first','second')]
    def write(item):
        store,index=item
        return store.user_id,index,store.command(activity(str(index),str(index)))['cursor']
    with ThreadPoolExecutor(max_workers=6) as pool:
        results=list(pool.map(write,[(s,i) for i in range(12) for s in stores]))
    for store in stores:
        assert sorted(v for uid,index,v in results if uid==store.user_id)==list(range(1,13))
        assert store.command(activity('0','0'))['cursor']==next(v for uid,index,v in results if uid==store.user_id and index==0)
    with pytest.raises(Exception):
        stores[0].import_plan([activity('ok','ok'),{**activity('bad','bad'),'expected_revision':4}])
    assert len(stores[0].records()['records'])==12


def test_postgres_snapshot_import_preserves_every_row_and_next_cursor(pg,tmp_path):
    accounts=AccountStore(pg,iterations=1000)
    uid=accounts.users.create('athlete@example.com',PASSWORD)['user_id']
    with accounts.transaction(write=True) as db:
        db.execute(update(User).where(User.user_id==uid).values(user_id=LEGACY_OWNER))
    source=tmp_path/'old.db';legacy(source)
    before=source.read_bytes()
    from euros_server.app import _adopt
    report=_adopt(accounts,pg,source,'athlete@example.com')['rows']
    assert report['review_jobs']['rows']==1
    store=Store(pg,LEGACY_OWNER)
    assert store.changes(after=40)['changes'][0]['revision']==3
    assert store.command(activity('new','new'))['cursor']==42
    assert source.read_bytes()==before
    with pytest.raises(ValueError,match='already imported'):
        import_snapshot(pg,source)


def test_postgres_orm_guards_cache_expiry_and_quotas(pg):
    from test_shared_storage import test_orm_scope_covers_reads_counts_aliases_deletes_and_rejects_unscoped_writes
    from euros_server.security import LimitedProvider
    accounts=AccountStore(pg,iterations=1000)
    stores=[Store(pg,accounts.users.create(n,PASSWORD)['user_id']) for n in ('first','second')]
    test_orm_scope_covers_reads_counts_aliases_deletes_and_rejects_unscoped_writes((accounts,stores))
    for store in stores:
        quota=LimitedProvider(None,store,hourly_food=1)
        quota.reserve('food_draft')
        with pytest.raises(Exception,match='model_hourly_limit'):
            quota.reserve('food_draft')


def test_postgres_import_refuses_uncheckpointed_source(pg,tmp_path):
    source=tmp_path/'old.db';legacy(source)
    Path(str(source)+'-wal').write_bytes(b'pending writes')
    with pytest.raises(ValueError,match='standalone'):
        import_snapshot(pg,source)


def test_postgres_shared_budget_export_delete_and_report(pg):
    from euros_server.account_data import delete_account, export_account, row_counts
    from euros_server.operator_view import account_report
    from euros_server.security import ESTIMATED_TOKENS, TokenBudget
    from euros_server.domain import DomainError
    accounts=AccountStore(pg,iterations=1000)
    stores=[Store(pg,accounts.users.create(n,PASSWORD)['user_id']) for n in ('first','second')]
    for store in stores:
        store.command(activity('a-'+store.user_id[-4:],'x'))
    # The advisory lock: concurrent accounts cannot both take the last reservation.
    budget=TokenBudget(pg,3*ESTIMATED_TOKENS['food_draft'])
    def take(store):
        try:
            budget.reserve(store.user_id,'food_draft');return True
        except DomainError:
            return False
    with ThreadPoolExecutor(max_workers=6) as pool:
        assert sum(pool.map(take,stores*4))==3
    first,second=stores
    snapshot=export_account(pg,first.user_id)
    assert len(snapshot['tables']['records'])==1 and snapshot['account']['username']=='first'
    removed=delete_account(pg,first.user_id)
    assert removed['rows']['records']==1 and accounts.users.read(first.user_id) is None
    with accounts.transaction() as db:
        assert not any(row_counts(db,first.user_id).values())
        assert row_counts(db,second.user_id)['records']==1
    # The per-account share under the same lock: one account, many threads.
    capped=TokenBudget(pg,100*ESTIMATED_TOKENS['food_draft'],account_monthly_tokens=2*ESTIMATED_TOKENS['food_draft'])
    def mine(_):
        try:
            capped.reserve(second.user_id,'food_draft');return True
        except DomainError:
            return False
    with ThreadPoolExecutor(max_workers=6) as pool:
        # second already holds some of the shared reservations above; whatever is left of
        # its 3,000-token share fits exactly that many 1,500-token reservations.
        held=capped.spent(user_id=second.user_id)
        assert sum(pool.map(mine,range(8)))==max(0,2*ESTIMATED_TOKENS['food_draft']-held)//ESTIMATED_TOKENS['food_draft']
    report=account_report(accounts,budget)
    assert [a['username'] for a in report['accounts']]==['second']
    assert report['model_budget']['spent_tokens']==capped.spent()
