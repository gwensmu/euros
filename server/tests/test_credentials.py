"""How secrets are stored."""
import pytest

from euros_server.credentials import (digest, hash_password, new_secret, same_digest,
                                      validate_password, verify_password)
from euros_server.domain import DomainError

PASSWORD = 'correct horse battery'


def test_password_hash_is_salted_and_verifiable():
    first, second = hash_password(PASSWORD, 1000), hash_password(PASSWORD, 1000)
    assert first != second and PASSWORD not in first
    assert verify_password(first, PASSWORD) and not verify_password(first, PASSWORD + '!')
    assert not verify_password(None, PASSWORD) and not verify_password('nonsense', PASSWORD)


def test_password_length_is_the_only_rule():
    assert validate_password(PASSWORD) == PASSWORD
    for bad in ('short', '             ', 'x' * 201, None, 12):
        with pytest.raises(DomainError, match='invalid_password'):
            validate_password(bad)


def test_issued_credentials_are_random_and_only_recognised_by_digest():
    value = new_secret()
    assert len(value) >= 32 and value != new_secret()
    assert digest(value) != value and same_digest(value, digest(value))
    assert not same_digest(value, digest(new_secret())) and not same_digest(value, None)
