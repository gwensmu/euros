"""Shared transport bounds and persistent inference allowances for the single-user service."""
import threading
import time
from datetime import datetime
from zoneinfo import ZoneInfo
from starlette.responses import JSONResponse
from .domain import DomainError


class BodyLimit:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http':
            return await self.app(scope, receive, send)
        limit = {'/v1/tracker/import': 4194304, '/v1/review/week': 1048576,
                 '/v1/interpret/food': 40000, '/v1/reminders/copy': 16384}.get(scope['path'], 1048576 if scope['path'].startswith('/mcp') else 262144)
        body = bytearray()
        while True:
            message = await receive()
            if message['type'] == 'http.disconnect':
                return
            chunk = message.get('body', b'')
            if len(body) + len(chunk) > limit:
                return await JSONResponse({'error': 'request_too_large'}, 413)(scope, receive, send)
            body.extend(chunk)
            if not message.get('more_body', False):
                break
        delivered = False
        async def bounded_receive():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {'type': 'http.request', 'body': bytes(body), 'more_body': False}
            return await receive()
        await self.app(scope, bounded_receive, send)


class LimitedProvider:
    """Reserve before spending; failures count because provider billing may have occurred.

    Daily and rolling-minute reservations survive service restarts. Concurrency is process
    local: deployment runs one Uvicorn worker. No user text or model outputs are stored.
    """
    def __init__(self, provider, store, daily_food=50, daily_review=5, per_minute=6, concurrent=2, clock=time.time):
        self.provider, self.store, self.clock = provider, store, clock
        self.limits = {'food_draft': daily_food, 'weekly_wrap_up': daily_review, 'reminder_copy': 12}
        self.per_minute, self.concurrent = per_minute, concurrent
        self.active = 0
        self.lock = threading.Lock()
        with store.connect() as db:
            db.execute('CREATE TABLE IF NOT EXISTS inference_requests (at REAL NOT NULL, day TEXT NOT NULL, kind TEXT NOT NULL)')
            db.execute('CREATE INDEX IF NOT EXISTS inference_requests_at ON inference_requests(at)')

    def reserve(self, kind):
        now = self.clock()
        day = datetime.fromtimestamp(now, ZoneInfo('America/Chicago')).date().isoformat()
        with self.store.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            if kind not in self.limits:
                raise DomainError('model_not_configured', 503)
            if db.execute('SELECT COUNT(*) FROM inference_requests WHERE day=? AND kind=?', (day, kind)).fetchone()[0] >= self.limits[kind]:
                raise DomainError('model_daily_limit', 429)
            if db.execute('SELECT COUNT(*) FROM inference_requests WHERE at>?', (now-60,)).fetchone()[0] >= self.per_minute:
                raise DomainError('model_rate_limit', 429)
            db.execute('DELETE FROM inference_requests WHERE day<? AND at<=?', (day, now-86400*7))
            db.execute('INSERT INTO inference_requests VALUES (?,?,?)', (now, day, kind))

    async def generate(self, **kwargs):
        with self.lock:
            if self.active >= self.concurrent:
                raise DomainError('model_busy', 429)
            self.active += 1
        try:
            self.reserve(kwargs['name'])
            return await self.provider.generate(**kwargs)
        finally:
            with self.lock:
                self.active -= 1
