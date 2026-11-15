"""How secrets are stored: password verifiers and the digests of issued credentials.

Nothing here is reversible. A password becomes a salted PBKDF2-HMAC-SHA256 verifier. Every
credential this service issues — session cookie, authorization code, access and refresh token
— is a random value kept only as its SHA-256 digest, so the database never holds anything a
client could present. Comparisons are constant time.
"""
import hashlib
import hmac
import secrets

from .domain import DomainError

MIN_PASSWORD, MAX_PASSWORD = 12, 200
# Interactive cost, not a stored preference: raising it only affects passwords set afterwards.
ITERATIONS = 600_000


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
