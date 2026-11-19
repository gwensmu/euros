"""How secrets are stored: password verifiers and the digests of issued credentials.

Nothing here is reversible. A password becomes a salted PBKDF2-HMAC-SHA256 verifier. Every
credential this service issues — session cookie, authorization code, access and refresh token
— is a random value kept only as its SHA-256 digest, so the database never holds anything a
client could present. Comparisons are constant time.
"""
import asyncio
import functools
import hashlib
import hmac
import secrets
import threading
from concurrent.futures import ThreadPoolExecutor

from .domain import DomainError

MIN_PASSWORD, MAX_PASSWORD = 12, 200
# Interactive cost, not a stored preference: raising it only affects passwords set afterwards.
ITERATIONS = 600_000
# Derivations allowed at once across the process. Each holds a CPU for tens of milliseconds;
# more than this only queues behind the deployment's single CPU anyway.
CONCURRENT_DERIVATIONS = 2
# Attempts allowed to wait for a derivation slot; past this, sign-in answers too_many_attempts
# at once rather than building an unbounded queue of guesses.
QUEUED_DERIVATIONS = 16
_deriving = ThreadPoolExecutor(max_workers=CONCURRENT_DERIVATIONS, thread_name_prefix='euros-password')
_pending = 0
_pending_lock = threading.Lock()


def _release(_):
    global _pending
    with _pending_lock:
        _pending -= 1


async def password_work(call, *args):
    """Run a call that derives a password verifier (sign-in, sign-up, password change) on its
    own small thread pool. One Uvicorn worker serves every account, so running it on the event
    loop would pause every other user's requests for each attempt, including a stranger's
    guesses; running it on the shared request threads would let a burst of guesses occupy them
    all. hashlib releases the GIL while deriving. The call's own database access fits in the
    connections reserved for it (see app.request_threads)."""
    global _pending
    with _pending_lock:
        if _pending >= CONCURRENT_DERIVATIONS + QUEUED_DERIVATIONS:
            raise DomainError('too_many_attempts', 429)
        _pending += 1
    future = _deriving.submit(functools.partial(call, *args))
    future.add_done_callback(_release)
    return await asyncio.wrap_future(future)


def new_secret(length=32):
    """A fresh credential value to hand out once and then only recognise by its digest."""
    return secrets.token_urlsafe(length)


def digest(value):
    """The stored form of an issued credential."""
    return hashlib.sha256(value.encode()).hexdigest()


def same_digest(value, stored):
    return bool(stored) and hmac.compare_digest(digest(value), stored)


def hash_password(password, iterations=ITERATIONS, salt=None):
    salt = salt or secrets.token_bytes(16)
    derived = hashlib.pbkdf2_hmac('sha256', password.encode(), salt, iterations)
    return f'pbkdf2_sha256${iterations}${salt.hex()}${derived.hex()}'


def verify_password(stored, password):
    """Constant-time verification; an unparsable or absent verifier never authenticates."""
    try:
        algorithm, iterations, salt, derived = (stored or '').split('$')
        if algorithm != 'pbkdf2_sha256':
            return False
        candidate = hashlib.pbkdf2_hmac('sha256', password.encode(), bytes.fromhex(salt), int(iterations))
    except (ValueError, AttributeError, TypeError):
        return False
    return hmac.compare_digest(candidate.hex(), derived)


def validate_password(value):
    if not isinstance(value, str) or not MIN_PASSWORD <= len(value) <= MAX_PASSWORD or not value.strip():
        raise DomainError('invalid_password', 422, rule=f'{MIN_PASSWORD}-{MAX_PASSWORD} characters')
    return value
