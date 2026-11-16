"""Account identities, authentication and password changes."""
import pytest

from euros_server.accounts import AccountStore
from euros_server.domain import DomainError
from euros_server.users import MAX_FAILURES

PASSWORD = 'correct horse battery'


@pytest.fixture
def users(tmp_path):
    return AccountStore(tmp_path / 'accounts.db', iterations=1000).users


def test_usernames_and_passwords_are_checked(users):
    for bad in ('ab', 'Has Space', 'x' * 33, 'Ünicode'):
        with pytest.raises(DomainError, match='invalid_username'):
            users.create(bad, PASSWORD)
    with pytest.raises(DomainError, match='invalid_password'):
        users.create('athlete', 'short')
    user = users.create('  Athlete  ', PASSWORD, display_name='  Sam  ')
    assert user['username'] == 'athlete' and user['display_name'] == 'Sam'
    with pytest.raises(DomainError, match='username_taken'):
        users.create('athlete', PASSWORD)


def test_an_account_is_readable_by_id_username_and_listing(users):
    user = users.create('athlete', PASSWORD)
    assert users.read(user['user_id']) == user and users.active(user['user_id']) == user
    assert users.by_username('ATHLETE ') == user and users.by_username(None) is None
    assert users.ids() == [user['user_id']] and users.count() == 1
    assert users.read('usr-nobody') is None
    # A verifier is never part of what the rest of the service sees.
    assert 'password_hash' not in user


def test_sign_in_is_throttled_after_repeated_failures(users):
    users.create('athlete', PASSWORD)
    assert users.authenticate('ATHLETE', PASSWORD)['username'] == 'athlete'
    for _ in range(MAX_FAILURES):
        with pytest.raises(DomainError, match='invalid_credentials'):
            users.authenticate('athlete', 'wrong password here')
    # The correct password is refused too while the account is cooling down.
    with pytest.raises(DomainError, match='too_many_attempts'):
        users.authenticate('athlete', PASSWORD)


def test_unknown_account_and_unknown_password_fail_alike(users):
    with pytest.raises(DomainError) as unknown:
        users.authenticate('nobody', PASSWORD)
    assert unknown.value.body == {'error': 'invalid_credentials'} and unknown.value.status == 401


def test_password_change_requires_the_current_one(users):
    user = users.create('athlete', PASSWORD)
    with pytest.raises(DomainError, match='invalid_credentials'):
        users.set_password(user['user_id'], 'not the password', 'a new long password')
    users.set_password(user['user_id'], PASSWORD, 'a new long password')
    assert users.authenticate('athlete', 'a new long password')
    with pytest.raises(DomainError, match='invalid_credentials'):
        users.authenticate('athlete', PASSWORD)


def test_an_operator_can_reset_a_forgotten_password(users):
    user = users.create('athlete', PASSWORD)
    users.reset_password(user['user_id'], 'a replacement password')
    assert users.authenticate('athlete', 'a replacement password')
    with pytest.raises(DomainError, match='invalid_credentials'):
        users.authenticate('athlete', PASSWORD)
    with pytest.raises(DomainError, match='invalid_password'):
        users.reset_password(user['user_id'], 'short')
    with pytest.raises(DomainError, match='user_not_found'):
        users.reset_password('usr-nobody', 'a replacement password')


def test_email_username_is_normalized_and_authenticates(users):
    user = users.create(' Athlete+training@Example.COM ', PASSWORD)
    assert user['username'] == 'athlete+training@example.com'
    assert users.authenticate('ATHLETE+TRAINING@EXAMPLE.COM', PASSWORD)['user_id'] == user['user_id']
    for bad in ('a@@example.com', 'a@example', 'a@-example.com', 'a @example.com'):
        with pytest.raises(DomainError, match='invalid_username'):
            users.create(bad, PASSWORD)
