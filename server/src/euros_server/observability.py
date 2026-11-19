"""One structured log line per request, and the traceback of any request that fails.

Lines are JSON on stderr, which the container runtime already collects and rotates. They name
the request, never its content: method, path without its query string, status, duration, the
account id (an opaque `usr-…`, not the username), the authenticating client and, for MCP, the
JSON-RPC method and tool name. Request and response bodies, query strings, headers, tokens and
cookies are never logged, so food, health and plan data stay out of the logs.

Every response carries `X-Request-ID`; quote it to find that request's line and any error.
"""
import json
import logging
import os
import secrets
import sys
import time
import traceback
from datetime import datetime, timezone

log = logging.getLogger('euros.request')
REQUEST_ID_HEADER = b'x-request-id'
# A request slower than this is logged as a warning, so slow endpoints stand out.
SLOW_MS = 1000
# Successful health checks run every 30 s; only a failing one is worth a line.
QUIET_PATHS = {'/healthz'}
MAX_PATH = 200


class JsonFormatter(logging.Formatter):
    def format(self, record):
        entry = {'ts': datetime.fromtimestamp(record.created, timezone.utc).isoformat(timespec='milliseconds'),
                 'level': record.levelname.lower(), 'logger': record.name, 'msg': record.getMessage(),
                 **getattr(record, 'fields', {})}
        if record.exc_info:
            # Database exceptions may embed bound record values or connection details.
            # Keep locations and type for diagnosis, never exception text or source lines.
            kind, _, trace = record.exc_info
            entry['exc'] = {'type': kind.__name__, 'frames': [
                {'file': frame.filename, 'line': frame.lineno, 'function': frame.name}
                for frame in traceback.extract_tb(trace)]}
        return json.dumps(entry, default=str)


def configure_logging(level=None, stream=None):
    """Send the service's own logs to stderr as JSON lines. Called by the server entry points,
    not by building an app, so tests and embedding applications keep their own logging."""
    logger = logging.getLogger('euros')
    if any(getattr(h, 'euros', False) for h in logger.handlers):
        return logger
    handler = logging.StreamHandler(stream or sys.stderr)
    handler.setFormatter(JsonFormatter())
    handler.euros = True
    logger.addHandler(handler)
    logger.setLevel((level or os.environ.get('EUROS_LOG_LEVEL') or 'INFO').upper())
    logger.propagate = False
    return logger


def _account(scope):
    principal = scope.get('euros_principal')
    if principal:
        return {'user_id': principal['user_id'], 'client': principal['client_id'], 'auth': principal['kind']}
    if scope.get('euros_session_user'):
        return {'user_id': scope['euros_session_user'], 'auth': 'session'}
    return {}


class RequestLog:
    """Outermost ASGI middleware: times each request and records how it ended."""

    def __init__(self, app, clock=time.perf_counter):
        self.app, self.clock = app, clock

    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http':
            return await self.app(scope, receive, send)
        request_id = secrets.token_hex(8)
        scope['euros_request_id'] = request_id
        started, status, sent = self.clock(), None, False

        async def send_with_id(message):
            nonlocal status, sent
            if message['type'] == 'http.response.start':
                status, sent = message['status'], True
                message = {**message, 'headers': [*message.get('headers', []),
                                                  (REQUEST_ID_HEADER, request_id.encode())]}
            await send(message)

        failure = None
        try:
            await self.app(scope, receive, send_with_id)
        except Exception as exc:  # noqa: BLE001 - logged here, answered below
            failure = exc
            if not sent:
                await send_with_id({'type': 'http.response.start', 'status': 500,
                                    'headers': [(b'content-type', b'application/json'),
                                                (b'cache-control', b'no-store')]})
                await send({'type': 'http.response.body',
                            'body': json.dumps({'error': 'internal_error', 'request_id': request_id}).encode()})
            status = 500
        finally:
            elapsed = round((self.clock() - started) * 1000, 1)
            if status is None:
                status = 499  # The client went away before an answer started.
            path = scope.get('path', '')[:MAX_PATH]
            if failure is None and path in QUIET_PATHS and status < 400:
                return
            fields = {'request_id': request_id, 'method': scope.get('method'), 'path': path,
                      'status': status, 'duration_ms': elapsed, **_account(scope)}
            if scope.get('euros_rpc'):
                fields.update(scope['euros_rpc'])
            if failure is not None:
                log.error('request failed', exc_info=failure, extra={'fields': fields})
            elif status >= 500 or elapsed >= SLOW_MS:
                log.warning('request', extra={'fields': fields})
            else:
                log.info('request', extra={'fields': fields})
