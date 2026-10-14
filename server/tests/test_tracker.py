import asyncio
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import json

import pytest
from starlette.testclient import TestClient
from euros_server.app import create_app
from euros_server.domain import DomainError, Store
from euros_server.tracker import TrackerStore, metrics, POLAR

API='app-'+'x'*40
MCP='mcp-'+'y'*40

@pytest.fixture
def service(tmp_path):
    store=Store(tmp_path/'euros.sqlite3')
    return store,TrackerStore(store)

def workout(key='polar:a',revision=0):
    return {'source_key':key,'expected_revision':revision,'deleted':False,'source_package':POLAR,
            'source_id':key,'source_title':None,'exercise_type':53,
            'source_modified_at':'2026-10-12T14:00:00Z','observed_at':'2026-10-13T14:00:00Z',
            'start_at':'2026-10-12T13:00:00Z','end_at':'2026-10-12T13:10:00Z','activity_id':None,
            'heart_rate_samples':[{'at':f'2026-10-12T13:{minute:02d}:00Z','bpm':bpm,'source_id':'hr:a','source_package':POLAR} for minute,bpm in [(0,100),(1,120),(2,140),(4,200),(5,160),(6,140),(10,80)]]}

def upload(tracker,*records,cid='test'):
    return tracker.ingest({'command_id':cid,'records':list(records)})

def test_retry_restart_and_separate_manual_feed(service):
    store,tracker=service
    original=upload(tracker,workout())
    assert upload(TrackerStore(Store(store.path)),workout())==original
    assert store.records()['records']==[] and store.changes()['changes']==[]
    assert tracker.read('polar:a')['workout']['revision']==1
    with pytest.raises(DomainError,match='idempotency_key_reused'):
        upload(tracker,{**workout(),'source_title':'changed'})

def test_concurrent_retries_one_revision(service):
    _,tracker=service
    with ThreadPoolExecutor(max_workers=5) as pool:
        results=list(pool.map(lambda _:upload(tracker,workout()),range(10)))
    assert all(result==results[0] for result in results)
    assert tracker.read('polar:a')['workout']['revision']==1

def test_cas_stale_observation_source_and_tombstone(service):
    _,tracker=service
    upload(tracker,workout())
    with pytest.raises(DomainError,match='tracker_revision_conflict'):upload(tracker,workout(),cid='stale')
    corrected=workout(revision=1);corrected['heart_rate_samples'][0]['bpm']=110
    upload(tracker,corrected,cid='correct')
    stale=workout(revision=2);stale['observed_at']='2026-10-12T13:00:00Z'
    with pytest.raises(DomainError,match='stale_tracker_observation'):upload(tracker,stale,cid='old')
    stale['observed_at']='2026-10-13T14:00:00Z';stale['source_modified_at']='2026-10-11T14:00:00Z'
    with pytest.raises(DomainError,match='stale_tracker_source'):upload(tracker,stale,cid='old-source')
    tombstone={'source_key':'polar:a','expected_revision':2,'deleted':True,'observed_at':'2026-10-13T14:01:00Z'}
    first=upload(tracker,tombstone,cid='delete')
    assert upload(tracker,tombstone,cid='delete')==first
    assert tracker.read('polar:a')['workout']['deleted']
    assert 'metrics' not in tracker.read('polar:a')['workout']
    assert tracker.list('2026-10-12','2026-10-12')['workouts'][0]['deleted']

def test_whole_batch_rolls_back_on_conflict(service):
    _,tracker=service
    upload(tracker,workout())
    with pytest.raises(DomainError):upload(tracker,workout('polar:b'),workout(),cid='batch')
    assert tracker.read('polar:b')=={'workout':None}

def test_gap_coverage_timeweighted_average_and_explicit_bands():
    result=metrics(workout(),[120,160])
    assert result['covered_seconds']==240
    assert result['coverage_fraction']==.4
    assert result['average_bpm']==145
    assert result['peak_bpm']==200
    assert result['time_bands']['covered_seconds']==[60,60,120]
    assert sum(w['covered_seconds'] for w in result['intervals'])==240
    assert metrics(workout())['time_bands'] is None
    for boundaries in ([],[160,120],[120,120],[True],[300.1]):
        with pytest.raises(DomainError):metrics(workout(),boundaries)

def test_zero_and_one_samples_are_unknown_coverage_not_zero_hr():
    w=workout();w['heart_rate_samples']=[]
    assert metrics(w)['average_bpm'] is None and metrics(w)['peak_bpm'] is None
    w['heart_rate_samples']=workout()['heart_rate_samples'][:1]
    assert metrics(w)['average_bpm'] is None
    assert metrics(w)['peak_bpm']==100 and metrics(w)['covered_seconds']==0

def test_fractional_sixty_second_gap_and_timewindow_boundary():
    w=workout();w['heart_rate_samples']=[{'at':at,'bpm':100,'source_id':'hr','source_package':POLAR} for at in ['2026-10-12T13:04:59.500Z','2026-10-12T13:05:59.500Z','2026-10-12T13:06:59.501Z']]
    result=metrics(w)
    assert result['covered_seconds']==60
    assert result['intervals'][0]['covered_seconds']==.5
    assert result['intervals'][1]['covered_seconds']==59.5

def test_downsample_preserves_peak_and_original_metrics():
    w=workout();start=datetime.fromisoformat(w['start_at'].replace('Z','+00:00'))
    w['end_at']=(start+timedelta(seconds=1200)).isoformat()
    w['heart_rate_samples']=[{'at':(start+timedelta(seconds=i)).isoformat(),'bpm':210 if i==523 else 100,'source_id':'hr','source_package':POLAR} for i in range(1200)]
    result=metrics(w)
    assert len(result['curve'])<=600 and result['curve_downsampled']
    assert max(s['bpm'] for s in result['curve'])==210
    assert result['sample_count']==1200 and result['covered_seconds']==1199

@pytest.mark.parametrize('mutate',[
    lambda w:w.update(source_package='com.fitbit.FitbitMobile'),
    lambda w:w.update(expected_revision=-1),lambda w:w.update(expected_revision=True),
    lambda w:w.update(source_title=123),lambda w:w.update(source_title='x'*501),
    lambda w:w.update(exercise_type=True),lambda w:w.update(end_at=w['start_at']),
    lambda w:w.update(observed_at='2099-01-01T00:00:00Z'),
    lambda w:w['heart_rate_samples'][0].update(bpm=float('nan')),
    lambda w:w['heart_rate_samples'][0].update(bpm=0),
    lambda w:w['heart_rate_samples'][0].update(source_package='other'),
    lambda w:w['heart_rate_samples'].reverse(),
    lambda w:w['heart_rate_samples'].append(w['heart_rate_samples'][-1]),
])
def test_invalid_inputs_never_write(service,mutate):
    _,tracker=service;w=workout();mutate(w)
    with pytest.raises(DomainError):upload(tracker,w)
    assert tracker.list('2026-10-12','2026-10-18')['workouts']==[]

def test_source_identity_cannot_be_duplicated_or_changed(service):
    _,tracker=service;upload(tracker,workout())
    changed=workout('other');changed['source_id']='polar:a'
    with pytest.raises(DomainError,match='duplicate_tracker_source_identity'):upload(tracker,changed,cid='dup')
    changed=workout(revision=1);changed['source_id']='other'
    with pytest.raises(DomainError,match='tracker_identity_changed'):upload(tracker,changed,cid='change')

def test_links_do_not_complete_and_final_batch_move_is_order_independent(service):
    store,tracker=service
    store.command({'command_id':'activity','entity_type':'activities','entity_id':'planned','expected_revision':0,'operation':'upsert','payload':{'title':'BJJ','kind':'bjj','scheduled_at':'2026-10-12T13:00:00Z','status':'pending'}})
    a=workout();a['activity_id']='planned';upload(tracker,a)
    b=workout('polar:b');b['activity_id']='planned'
    with pytest.raises(DomainError,match='tracker_activity_already_linked'):upload(tracker,b,cid='duplicate-link')
    upload(tracker,b,workout(revision=1),cid='transfer-link')
    assert tracker.read('polar:b')['workout']['activity_id']=='planned'
    assert store.records()['records'][0]['payload']['status']=='pending'
    assert len(store.changes()['changes'])==1

def test_chicago_dates_and_tombstone_ranges(service):
    _,tracker=service;w=workout();w['start_at']='2026-10-13T02:00:00Z';w['end_at']='2026-10-13T03:00:00Z';w['heart_rate_samples']=[]
    upload(tracker,w)
    assert len(tracker.list('2026-10-12','2026-10-12')['workouts'])==1
    assert tracker.list('2026-10-13','2026-10-13')['workouts']==[]
    for a,b in [('20261012','2026-10-12'),('2026-10-13','2026-10-12'),('2025-01-01','2026-10-12')]:
        with pytest.raises(DomainError):tracker.list(a,b)

def test_ingest_auth_body_bounds_and_generic_command_rejection(service):
    store,tracker=service
    with TestClient(create_app(store,API,MCP),base_url='http://localhost') as client:
        for token in (None,MCP):
            headers={} if token is None else {'Authorization':'Bearer '+token}
            assert client.post('/v1/tracker/import',content='malformed',headers=headers).status_code==401
        headers={'Authorization':'Bearer '+API}
        assert client.post('/v1/tracker/import',json={'command_id':'http','records':[workout()]},headers=headers).status_code==200
        assert client.post('/v1/tracker/import',content=' '*4194305,headers=headers).status_code==413
        assert client.get('/v1/tracker/workouts?date_from=2026-10-12&date_to=2026-10-18',headers=headers).json()['workouts'][0]['revision']==1
        bad={'command_id':'no','entity_type':'tracker_workouts','entity_id':'polar:a','expected_revision':1,'operation':'delete','payload':{}}
        assert client.post('/v1/commands',json=bad,headers=headers).status_code==422
        mcpheaders={'Authorization':'Bearer '+MCP,'Content-Type':'application/json','Accept':'application/json, text/event-stream'}
        response=client.post('/mcp',json={'jsonrpc':'2.0','id':1,'method':'tools/list','params':{}},headers=mcpheaders).json()
        tools={t['name']:t for t in response['result']['tools']}
        assert tools['read_tracker_workout']['annotations']['readOnlyHint']
        assert tools['list_tracker_workouts']['annotations']['readOnlyHint']
        assert not any('import' in name for name in tools)
        response=client.post('/mcp',json={'jsonrpc':'2.0','id':2,'method':'tools/call','params':{'name':'read_tracker_workout','arguments':{'source_key':'polar:a','zone_boundaries_bpm':[120,160]}}},headers=mcpheaders).json()
        assert not response['result'].get('isError',False)
        assert '240' in json.dumps(response['result'])

def test_bounds_and_missing_link_preserve_empty_store(service):
    _,tracker=service
    for records in ([],[workout()]*21,[workout(),workout()]):
        with pytest.raises(DomainError):tracker.ingest({'command_id':'bad','records':records})
    missing=workout();missing['activity_id']='not-saved'
    with pytest.raises(DomainError,match='unknown_linked_activity'):upload(tracker,missing)
    huge=workout();huge['heart_rate_samples']*=3000
    with pytest.raises(DomainError,match='invalid_tracker_samples'):upload(tracker,huge)
    outside=workout();outside['heart_rate_samples'][0]['at']='2026-10-11T13:00:00Z'
    with pytest.raises(DomainError,match='unordered_or_outside_tracker_samples'):upload(tracker,outside)
    assert tracker.list('2026-10-12','2026-10-18')['workouts']==[]

def test_contract_fixture_and_missing_vendor_labels(service):
    from pathlib import Path
    _,tracker=service
    command=json.loads((Path(__file__).resolve().parents[2]/'contracts/tracker-import.json').read_text())
    tracker.ingest(command)
    result=tracker.read(command['records'][0]['source_key'])['workout']
    assert result['training_benefit'] is None and result['polar_zone_definitions'] is None
    assert result['heart_rate_source_ids']==['synthetic-hr-1']
