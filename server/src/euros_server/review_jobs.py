"""Durable recap jobs. Disconnecting a client never cancels a model request."""
import asyncio
import json
import time
import uuid
from .domain import DomainError, canonical
from .llm import weekly_facts

class ReviewJobs:
    def __init__(self, store, models, tracker):
        self.store, self.models, self.tracker = store, models, tracker
        with store.connect() as db:
            db.execute('CREATE TABLE IF NOT EXISTS review_jobs (id TEXT PRIMARY KEY, week TEXT NOT NULL, input TEXT NOT NULL, state TEXT NOT NULL, result TEXT, error TEXT, created REAL NOT NULL, updated REAL NOT NULL)')

    def start(self, body):
        if not isinstance(body, dict) or set(body) != {'request_id','week_start','records'}:
            raise DomainError('invalid_week_request')
        try:
            identifier = str(uuid.UUID(body['request_id']))
        except (ValueError, TypeError, AttributeError):
            raise DomainError('invalid_request_id') from None
        snapshot = {k:body[k] for k in ('week_start','records')}
        weekly_facts(snapshot, self.models.today())
        encoded = canonical(snapshot)
        with self.store.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            old = db.execute('SELECT input FROM review_jobs WHERE id=?', (identifier,)).fetchone()
            if old and old['input'] != encoded:
                raise DomainError('request_id_conflict',409)
            if not old:
                active = db.execute("SELECT id FROM review_jobs WHERE week=? AND state IN ('queued','running')",(body['week_start'],)).fetchone()
                if active:
                    raise DomainError('review_already_running',409)
                now=time.time()
                db.execute('INSERT INTO review_jobs VALUES (?,?,?,?,NULL,NULL,?,?)',(identifier,body['week_start'],encoded,'queued',now,now))
        return self.read(identifier)

    def read(self, identifier):
        with self.store.connect() as db:
            row = db.execute('SELECT * FROM review_jobs WHERE id=?',(identifier,)).fetchone()
        if row is None:
            raise DomainError('review_not_found',404)
        return dict(job_id=row['id'],week_start=row['week'],status=row['state'],
                    result=json.loads(row['result']) if row['result'] else None,error=row['error'])

    def recover(self):
        # Never repeat a possibly billed call after restart. Queued jobs can safely proceed.
        with self.store.connect() as db:
            db.execute("UPDATE review_jobs SET state='failed',error='review_interrupted',updated=? WHERE state='running'",(time.time(),))

    async def run_next(self):
        with self.store.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row=db.execute("SELECT * FROM review_jobs WHERE state='queued' ORDER BY created LIMIT 1").fetchone()
            if row is None:return False
            db.execute("UPDATE review_jobs SET state='running',updated=? WHERE id=?",(time.time(),row['id']))
        result=None;error=None
        try:
            async with asyncio.timeout(150):
                result=await self.models.week(json.loads(row['input']),store=self.store,tracker=self.tracker)
        except asyncio.CancelledError:
            error='review_interrupted'
            raise
        except DomainError as exc:
            error=exc.body['error']
        except TimeoutError:
            error='model_timeout'
        except Exception:
            error='model_unavailable'
        finally:
            with self.store.connect() as db:
                db.execute('UPDATE review_jobs SET state=?,result=?,error=?,updated=? WHERE id=?',
                    ('completed' if result is not None else 'failed',canonical(result) if result is not None else None,error,time.time(),row['id']))
        return True

    async def serve(self):
        while True:
            if not await self.run_next():await asyncio.sleep(0.5)
