import asyncio
import json
import httpx
import pytest
from starlette.testclient import TestClient
from euros_server.app import create_app
from euros_server.domain import Store,DomainError
from euros_server.llm import ModelService
from euros_server.reminder_copy import ReminderCopyService
from euros_server.security import LimitedProvider

class Fake:
 def __init__(self,joke='gi'):self.joke=joke;self.calls=0
 async def generate(self,**kwargs):self.calls+=1;return {'joke_id':self.joke}

def body(**extra):return dict(kind='training',context_key='a'*64,allow_skip_joke=False,facts='Comp class is at 6:30 PM.',recent_joke_ids=[],**extra)

def test_cache_persists_and_binds_facts(tmp_path):
 store=Store(tmp_path/'db');provider=Fake();models=ModelService(provider)
 first=asyncio.run(ReminderCopyService(models,store).copy(body()))
 assert first['joke_id']=='gi'
 assert asyncio.run(ReminderCopyService(models,store).copy(body()))==first
 assert provider.calls==1
 changed=body();changed['facts']='Comp class is at 7:00 PM.'
 asyncio.run(ReminderCopyService(models,store).copy(changed))
 assert provider.calls==2 and store.records()=={'records':[]}

def test_unapproved_or_unsupported_skip_joke_is_rejected(tmp_path):
 for joke in ('weasel_out','invented'):
  with pytest.raises(DomainError):asyncio.run(ReminderCopyService(ModelService(Fake(joke)),Store(tmp_path/joke)).copy(body()))
 allowed=body();allowed['allow_skip_joke']=True
 assert asyncio.run(ReminderCopyService(ModelService(Fake('weasel_out')),Store(tmp_path/'ok')).copy(allowed))['joke_id']=='weasel_out'

def test_route_auth_validation_and_daily_budget(tmp_path):
 provider=Fake();store=Store(tmp_path/'db')
 with TestClient(create_app(store,'a'*40,'b'*40,model_service=ModelService(provider)),base_url='http://localhost') as client:
  path='/v1/reminders/copy';headers={'Authorization':'Bearer '+'a'*40}
  assert client.post(path,json=body()).status_code==401
  assert client.post(path,headers={'Authorization':'Bearer '+'b'*40},json=body()).status_code==401
  assert client.post(path,headers=headers,json={}).status_code==422
  assert provider.calls==0
  assert client.post(path,headers=headers,json=body()).status_code==200
  assert client.post(path,headers=headers,json=body()).status_code==200
  assert provider.calls==1
 limited=LimitedProvider(Fake(),store,per_minute=100)
 for _ in range(11):asyncio.run(limited.generate(name='reminder_copy'))
 with pytest.raises(DomainError) as e:asyncio.run(limited.generate(name='reminder_copy'))
 assert e.value.body['error']=='model_daily_limit'
