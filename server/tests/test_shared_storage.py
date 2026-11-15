"""Isolation with colliding identities in the same SQLite file, not just separate fixtures."""
import asyncio
import copy
import json
from concurrent.futures import ThreadPoolExecutor
from datetime import date
from pathlib import Path

import pytest
from euros_server.accounts import AccountStore
from euros_server.database import LEGACY_OWNER
from euros_server.domain import DomainError, Store
from euros_server.catalog import Catalog
from euros_server.food_cache import FoodCache
from euros_server.llm import ModelService
from euros_server.meal_week import MealWeeks
from euros_server.reminder_copy import ReminderCopyService
from euros_server.security import LimitedProvider
from euros_server.tracker import TrackerStore
from euros_server.workspace import Workspaces
from conftest import PASSWORD, SilentProvider
from test_isolation import two_accounts, activity
from test_food_cache import Models
from test_reminder_copy import Fake, body


@pytest.fixture
def pair(tmp_path):
    accounts = AccountStore(tmp_path / 'euros.db', iterations=1000)
    users = [accounts.users.create(name, PASSWORD)['user_id'] for name in ('first','second')]
    return accounts, [Store(accounts.path, uid) for uid in users]


def test_colliding_ids_retries_conflicts_tombstones_and_cursors(pair):
    _, (a,b) = pair
    ac,bc=activity('same','Private A'),activity('same','Private B')
    assert a.command(ac)['cursor'] == b.command(bc)['cursor'] == 1
    assert a.command(ac)['record']['payload']['title'] == 'Private A'
    assert b.command(bc)['record']['payload']['title'] == 'Private B'
    deletion={**ac,'command_id':'delete','expected_revision':1,'operation':'delete','payload':{}}
    assert a.command(deletion)['cursor']==2
    assert b.records()['records'][0]['payload']['title']=='Private B'
    assert b.changes(limit=1)['has_more'] is False
    assert b.changes(after=1)['changes']==[]
    with pytest.raises(DomainError) as error:
        b.command({**bc,'command_id':'stale','expected_revision':0})
    assert error.value.body['current']['payload']['title']=='Private B'
    assert 'Private A' not in json.dumps(error.value.body)
    assert 'user_id' not in json.dumps(b.records())
    with a.session(write=True) as connection, pytest.raises(DomainError,match='account_context_mismatch'):
        b.command(activity('new','wrong transaction'),_db=connection)


def test_same_source_and_link_ids_cannot_cross_accounts(pair):
    _,(a,b)=pair
    record=activity('linked','Only A');a.command(record)
    command=json.loads((Path(__file__).resolve().parents[2]/'contracts/tracker-import.json').read_text())
    command['records'][0]['activity_id']='linked'
    with pytest.raises(DomainError,match='unknown_linked_activity'):
        TrackerStore(b).ingest(command)
    assert TrackerStore(b).list('2026-10-12','2026-10-12')['workouts']==[]
    TrackerStore(a).ingest(command)
    key=command['records'][0]['source_key']
    assert TrackerStore(b).read(key)=={'workout':None}
    b.command({**record,'payload':{**record['payload'],'title':'Only B'}})
    assert TrackerStore(b).ingest(command)['records'][0]['revision']==1


def test_catalog_drafts_and_expiry_stay_with_the_owner(pair):
    _,(a,b)=pair
    ca,cb=Catalog(a),Catalog(b)
    identity=next(iter(ca.snapshot()['foods']))
    food=ca.snapshot()['foods'][identity];revision=food.pop('revision');food['name']='Private replacement'
    ca.upsert('food',identity,food,revision)
    assert cb.snapshot()['foods'][identity]['name']!='Private replacement'
    ma,mb=MealWeeks(a),MealWeeks(b)
    draft=ma.draft('2026-11-23',today=date(2026,11,15))
    with pytest.raises(DomainError,match='draft_not_found'):
        mb.apply(draft['draft_id'])
    # Cleanup for A must not erase B's expired drafts.
    with b.connect() as db:
        db.execute('INSERT INTO meal_week_drafts VALUES (?,?,?,?)',(b.user_id,'b-old','2000-01-01','{}'))
    ma._store_draft({'own':'draft'})
    with b.connect() as db:
        assert db.execute('SELECT 1 FROM meal_week_drafts WHERE user_id=? AND draft_id=?',(b.user_id,'b-old')).fetchone()


def test_food_and_reminder_caches_and_quota_are_account_owned(pair):
    accounts,(a,b)=pair
    async def food():
        models=Models()
        await FoodCache(a,models).food({'text':'same food'})
        await FoodCache(b,models).food({'text':'same food'})
        await FoodCache(a,models).food({'text':'same food'})
        assert len(models.calls)==2
    asyncio.run(food())
    provider=Fake()
    for store in (a,b,a):asyncio.run(ReminderCopyService(ModelService(provider),store).copy(body()))
    assert provider.calls==2
    one=LimitedProvider(provider,a,daily_food=1);two=LimitedProvider(provider,b,daily_food=1)
    one.reserve('food_draft');two.reserve('food_draft')
    with pytest.raises(DomainError,match='model_daily_limit'):one.reserve('food_draft')
    services=Workspaces(accounts,ModelService(SilentProvider()))
    with accounts.connect() as db:db.execute('UPDATE users SET disabled=1 WHERE user_id=?',(a.user_id,))
    with pytest.raises(DomainError,match='unauthorized'):services.get(a.user_id)


def test_authenticated_identity_cannot_be_selected_by_client_and_parallel_requests_do_not_mix(service):
    a,b=two_accounts(service)
    aid=a('GET','/v1/account').json()['user_id'];bid=b('GET','/v1/account').json()['user_id']
    assert aid!=bid
    assert a('GET','/v1/account?user_id='+bid).json()['user_id']==aid
    assert a('POST','/v1/commands',json={**activity('same','A'),'user_id':bid}).status_code==422
    a('POST','/v1/commands',json=activity('same','A'))
    b('POST','/v1/commands',json=activity('same','B'))
    def read(item):
        call,title=item
        for _ in range(5):
            response=call('GET','/v1/records?user_id='+aid)
            assert [r['payload']['title'] for r in response.json()['records']]==[title]
    with ThreadPoolExecutor(max_workers=4) as pool:list(pool.map(read,[(a,'A'),(b,'B')]*2))


def test_orm_scope_covers_reads_counts_aliases_deletes_and_rejects_unscoped_writes(pair):
    from sqlalchemy import select, func, delete, update, text
    from sqlalchemy.orm import aliased
    from euros_server.models import RecordRow, User
    _, (a, b) = pair
    a.command(activity('same', 'A'))
    b.command(activity('same', 'B'))
    with a.session() as session:
        assert [json.loads(r.payload)['title'] for r in session.scalars(select(RecordRow))] == ['A']
        assert session.get(RecordRow, (b.user_id, 'activities', 'same')) is None
        assert session.scalar(select(func.count(RecordRow.entity_id))) == 1
        alias = aliased(RecordRow)
        assert [r.user_id for r in session.scalars(select(alias))] == [a.user_id]
        for statement in (text('SELECT * FROM records'), select(User), update(RecordRow).values(user_id=b.user_id)):
            with pytest.raises(ValueError):
                session.execute(statement)
    with pytest.raises(ValueError), a.session(write=True) as session:
        row = session.get(RecordRow, (a.user_id, 'activities', 'same'))
        row.user_id = b.user_id
    with a.session(write=True) as session:
        session.execute(delete(RecordRow))
    assert a.records()['records'] == []
    assert b.records()['records'][0]['payload']['title'] == 'B'


def test_failed_batch_rolls_back_records_history_and_idempotency(pair):
    _, (a,b) = pair
    commands = [activity('first','valid'), {**activity('second','invalid'),'expected_revision':3}]
    with pytest.raises(DomainError, match='revision_conflict'):
        a.import_plan(commands)
    assert a.records()['records'] == []
    assert a.changes()['changes'] == []
    assert a.command(commands[0])['cursor'] == 1
    assert b.changes()['changes'] == []


def test_concurrent_writes_allocate_independent_contiguous_account_cursors(pair):
    _, (a,b) = pair
    def write(item):
        store, index = item
        return store.user_id, store.command(activity(str(index),str(index)))['cursor']
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(write, [(store,index) for index in range(8) for store in (a,b)]))
    for store in (a,b):
        assert sorted(cursor for uid,cursor in results if uid == store.user_id) == list(range(1,9))
        assert len(store.records()['records']) == 8
