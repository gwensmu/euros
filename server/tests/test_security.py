import asyncio
import threading
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import pytest
from starlette.testclient import TestClient
from euros_server.app import create_app
from euros_server.domain import Store, DomainError
from euros_server.security import BodyLimit, LimitedProvider


def command(size):
    return dict(command_id='test', entity_type='activities', entity_id='test', expected_revision=0,
                operation='upsert', payload={'title':'x'*size,'kind':'bjj','status':'pending','scheduled_at':'2026-10-14T10:00:00-05:00'})


def test_both_transports_and_domain_reject_oversized_command(tmp_path):
    store=Store(tmp_path/'db')
    with TestClient(create_app(store,'a'*40,'b'*40),base_url='http://localhost') as client:
        assert client.post('/v1/commands',json=command(300000),headers={'Authorization':'Bearer '+'a'*40}).status_code==413
        r=client.post('/mcp',json={'jsonrpc':'2.0','id':1,'method':'tools/call','params':{'name':'apply_command','arguments':{'command':command(300000)}}},headers={'Authorization':'Bearer '+'b'*40,'Accept':'application/json, text/event-stream'})
        assert r.json()['result']['isError'] is True
        assert 'request_too_large' in r.text
        assert client.post('/mcp',content=b'x'*1048577,headers={'Authorization':'Bearer '+'b'*40}).status_code==413
        assert client.post('/mcp',content=b'x'*1048577).status_code==401
    with pytest.raises(DomainError,match='request_too_large'):store.command(command(300000))
    assert store.records()['records']==[]
    assert store.command(command(100))['record']['payload']['title']=='x'*100


def test_streaming_limit_without_content_length_and_tracker_exception():
    async def run(path, size, expected):
        called=[];sent=[]
        async def app(scope,receive,send):
            called.append(len((await receive())['body']))
        chunks=iter([{'type':'http.request','body':b'x'*size,'more_body':True}, {'type':'http.request','body':b'x','more_body':False}])
        async def receive():return next(chunks)
        async def send(message):sent.append(message)
        await BodyLimit(app)({'type':'http','path':path},receive,send)
        if expected:assert called==[size+1]
        else:assert not called and sent[0]['status']==413
    asyncio.run(run('/mcp',1048576,False))
    asyncio.run(run('/v1/tracker/import',1048576,True))
    asyncio.run(run('/v1/tracker/import',4194304,False))


class Provider:
    async def generate(self, **kwargs):return 'ok'


def test_persistent_daily_budget_and_day_reset(tmp_path):
    store=Store(tmp_path/'db');now=[datetime(2026,10,14,12,tzinfo=timezone.utc).timestamp()]
    def limiter():return LimitedProvider(Provider(),store,daily_food=2,daily_review=1,clock=lambda:now[0])
    limiter().reserve('food_draft');limiter().reserve('food_draft')
    with pytest.raises(DomainError,match='model_daily_limit'):limiter().reserve('food_draft')
    limiter().reserve('weekly_wrap_up')
    with pytest.raises(DomainError,match='model_daily_limit'):limiter().reserve('weekly_wrap_up')
    now[0]+=86400
    limiter().reserve('food_draft')


def test_minute_budget_shared_between_kinds_and_atomic(tmp_path):
    store=Store(tmp_path/'db');lim=LimitedProvider(Provider(),store,per_minute=2,clock=lambda:1000000)
    lim.reserve('food_draft');lim.reserve('weekly_wrap_up')
    with pytest.raises(DomainError,match='model_rate_limit'):lim.reserve('food_draft')
    lim.clock=lambda:1000060
    lim.reserve('food_draft')
    other=LimitedProvider(Provider(),store,per_minute=2,clock=lambda:1000060)
    def reserve(_):
        try:other.reserve('food_draft');return True
        except DomainError:return False
    with ThreadPoolExecutor(max_workers=4) as pool:assert sum(pool.map(reserve,range(4)))==1


def test_concurrency_failure_and_cancellation_release_capacity(tmp_path):
    async def run():
        entered=asyncio.Event();release=asyncio.Event()
        class Blocking:
            async def generate(self,**kwargs):entered.set();await release.wait();raise RuntimeError('provider failed')
        store=Store(tmp_path/'db');lim=LimitedProvider(Blocking(),store,concurrent=1)
        task=asyncio.create_task(lim.generate(name='food_draft'));await entered.wait()
        with pytest.raises(DomainError,match='model_busy'):await lim.generate(name='food_draft')
        task.cancel()
        with pytest.raises(asyncio.CancelledError):await task
        assert lim.active==0
        release.set()
        with pytest.raises(RuntimeError):await lim.generate(name='food_draft')
        assert lim.active==0
        with store.connect() as db:assert db.execute('SELECT COUNT(*) FROM inference_requests').fetchone()[0]==2
    asyncio.run(run())


def test_api_and_mcp_share_inference_allowance(tmp_path):
    from euros_server.llm import ModelService
    class DraftProvider:
        calls=0
        async def generate(self,**kwargs):
            self.calls+=1
            return {'nutrients':dict.fromkeys(['kcal','protein_g','carbs_g','fat_g'],'1'),'quantity_g':None,'clarification':None}
    provider=DraftProvider();store=Store(tmp_path/'db')
    with TestClient(create_app(store,'a'*40,'b'*40,model_service=ModelService(provider)),base_url='http://localhost') as client:
        for i in range(6):
            if i%2:
                r=client.post('/mcp',json={'jsonrpc':'2.0','id':i,'method':'tools/call','params':{'name':'interpret_food','arguments':{'text':'synthetic'}}},headers={'Authorization':'Bearer '+'b'*40,'Accept':'application/json, text/event-stream'})
                assert not r.json()['result'].get('isError')
            else:assert client.post('/v1/interpret/food',json={'text':'synthetic'},headers={'Authorization':'Bearer '+'a'*40}).status_code==200
        r=client.post('/v1/interpret/food',json={'text':'synthetic'},headers={'Authorization':'Bearer '+'a'*40})
        assert r.status_code==429 and r.json()['error']=='model_rate_limit'
    assert provider.calls==6
