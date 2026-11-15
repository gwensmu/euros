import hashlib
import json
import sqlite3
from pathlib import Path
import pytest

from conftest import PASSWORD
from euros_server.accounts import AccountStore
from euros_server.database import LEGACY_OWNER
from euros_server.domain import Store
from euros_server.storage_migration import consolidate, import_training
from euros_server.app import create_service
from test_isolation import activity


def legacy(path):
    path.parent.mkdir(parents=True,exist_ok=True)
    command=activity('same','Legacy')
    record={'entity_type':'activities','entity_id':'same','revision':3,'deleted':False,
            'payload':command['payload'],'updated_at':'2026-11-15T12:00:00Z'}
    result={'record':record,'cursor':41}
    with sqlite3.connect(path) as db:
        db.executescript('''PRAGMA user_version=1;
          CREATE TABLE records(entity_type TEXT,entity_id TEXT,revision INTEGER,deleted INTEGER,payload TEXT,updated_at TEXT,PRIMARY KEY(entity_type,entity_id));
          CREATE TABLE changes(cursor INTEGER PRIMARY KEY AUTOINCREMENT,record TEXT NOT NULL);
          CREATE TABLE commands(command_id TEXT PRIMARY KEY,digest TEXT,result TEXT);
          CREATE TABLE review_jobs(id TEXT PRIMARY KEY,state TEXT,result TEXT);
        ''')
        db.execute('INSERT INTO records VALUES (?,?,?,?,?,?)',('activities','same',3,0,json.dumps(record['payload']),record['updated_at']))
        db.execute('INSERT INTO changes VALUES (?,?)',(41,json.dumps(record)))
        db.execute('INSERT INTO commands VALUES (?,?,?)',('historic','digest',json.dumps(result)))
        db.execute('INSERT INTO review_jobs VALUES (?,?,?)',('old','completed','private historical result'))
    return result


def test_personal_v1_upgrade_preserves_history_and_cursor(tmp_path):
    path=tmp_path/'old.db';result=legacy(path)
    store=Store(path)
    assert store.changes(after=40)['changes'][0]['revision']==3
    assert store.command(activity('second','new'))['cursor']==42
    with store.connect() as db:
        assert json.loads(db.execute('SELECT result FROM commands WHERE user_id=? AND command_id=?',(LEGACY_OWNER,'historic')).fetchone()[0])==result
        assert db.execute('SELECT result FROM review_jobs').fetchone()[0]=='private historical result'
        assert db.execute('PRAGMA user_version').fetchone()[0]==2
    with pytest.raises(ValueError, match='Unknown account'):
        Store(path,'usr-'+'f'*32)


def test_consolidation_preserves_two_users_with_colliding_ids_and_authentication(tmp_path):
    accounts=AccountStore(tmp_path/'accounts.db',iterations=1000)
    users=[accounts.users.create(name,PASSWORD) for name in ('first','second')]
    paths=[]
    for user in users:
        p=tmp_path/'users'/user['user_id']/'euros.db';legacy(p);paths.append(p)
    source_hashes={p:hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
    with pytest.raises(ValueError,match='consolidate'):create_service(tmp_path)
    result=consolidate(tmp_path)
    assert result['accounts']==2
    shared=AccountStore(tmp_path/'euros.db',iterations=1000)
    assert shared.users.authenticate('first',PASSWORD)['user_id']==users[0]['user_id']
    for user in users:
        store=Store(shared.path,user['user_id'])
        assert store.changes(after=40)['changes'][0]['entity_id']=='same'
        assert store.command(activity('new','personal'))['cursor']==42
    with shared.connect() as db:
        assert db.execute('SELECT COUNT(*) FROM retired_account_data').fetchone()[0]==2
    assert source_hashes=={p:hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
    with pytest.raises(ValueError,match='already exists'):consolidate(tmp_path)


def test_import_refuses_nonempty_destination_and_does_not_partially_write(tmp_path):
    accounts=AccountStore(tmp_path/'shared.db',iterations=1000);uid=accounts.users.create('first',PASSWORD)['user_id']
    path=tmp_path/'legacy.db';legacy(path)
    import_training(accounts.path,path,uid)
    with pytest.raises(ValueError,match='already has data'):import_training(accounts.path,path,uid)
    other=accounts.users.create('second',PASSWORD)['user_id']
    with sqlite3.connect(path) as db:db.execute('CREATE TABLE unrecognized_private_data(secret TEXT)')
    with pytest.raises(ValueError,match='unsupported tables'):import_training(accounts.path,path,other)
    assert Store(accounts.path,other).records()['records']==[]


def test_failed_consolidation_never_installs_partial_database(tmp_path):
    accounts=AccountStore(tmp_path/'accounts.db',iterations=1000)
    accounts.users.create('first',PASSWORD)
    legacy(tmp_path/'users'/'usr-unknown'/'euros.db')
    with pytest.raises(ValueError,match='Unregistered'):consolidate(tmp_path)
    assert not (tmp_path/'euros.db').exists()
    assert (tmp_path/'accounts.db').exists()
