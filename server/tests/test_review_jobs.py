import asyncio
from datetime import date
from types import SimpleNamespace
import uuid
import pytest
from euros_server.domain import Store, DomainError
from euros_server.review_jobs import ReviewJobs


def body():
    return dict(request_id=str(uuid.uuid4()),week_start='2026-10-26',records=[])


def test_job_persists_idempotency_and_completion_across_clients(tmp_path):
    calls=[]
    async def generate(snapshot,**kwargs):
        calls.append(snapshot)
        return {'summary':'Saved result'}
    models=SimpleNamespace(today=lambda:date(2026,11,2),week=generate)
    store=Store(tmp_path/'db')
    jobs=ReviewJobs(store,models,None)
    request=body()
    assert jobs.start(request)['status']=='queued'
    assert jobs.start(request)['job_id']==request['request_id']
    asyncio.run(jobs.run_next())
    assert ReviewJobs(Store(store.path),models,None).read(request['request_id'])['result']=={'summary':'Saved result'}
    assert jobs.start(request)['status']=='completed'
    assert not asyncio.run(jobs.run_next())
    assert len(calls)==1


def test_running_request_returns_immediately_and_failures_are_terminal(tmp_path):
    async def scenario():
        started=asyncio.Event();finish=asyncio.Event()
        async def generate(*args,**kwargs):
            started.set();await finish.wait();raise DomainError('model_timeout',504)
        jobs=ReviewJobs(Store(tmp_path/'db'),SimpleNamespace(today=lambda:date(2026,11,2),week=generate),None)
        request=body();jobs.start(request)
        runner=asyncio.create_task(jobs.run_next());await started.wait()
        assert jobs.read(request['request_id'])['status']=='running'
        assert jobs.start(request)['status']=='running'
        with pytest.raises(DomainError):jobs.start(body())
        finish.set();await runner
        assert jobs.read(request['request_id'])['error']=='model_timeout'
        assert jobs.read(request['request_id'])['status']=='failed'
    asyncio.run(scenario())


def test_restart_marks_running_failed_without_repeating_paid_call(tmp_path):
    jobs=ReviewJobs(Store(tmp_path/'db'),SimpleNamespace(today=lambda:date(2026,11,2)),None)
    request=body();jobs.start(request)
    with jobs.store.connect() as db:db.execute("UPDATE review_jobs SET state='running'")
    jobs.recover()
    assert jobs.read(request['request_id'])['error']=='review_interrupted'
    assert not asyncio.run(jobs.run_next())


def test_request_id_cannot_be_reused_for_other_week(tmp_path):
    jobs=ReviewJobs(Store(tmp_path/'db'),SimpleNamespace(today=lambda:date(2026,11,2)),None)
    request=body();jobs.start(request)
    with pytest.raises(DomainError):jobs.start({**request,'week_start':'2026-10-19'})
