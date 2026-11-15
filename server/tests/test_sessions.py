"""Browser sessions for the account site."""
import pytest

from euros_server.accounts import AccountStore

PASSWORD = 'correct horse battery'


@pytest.fixture
def accounts(tmp_path):
    return AccountStore(tmp_path / 'accounts.db', iterations=1000)


def test_sessions_end_individually_and_in_bulk(accounts):
    user = accounts.users.create('athlete', PASSWORD)
    sessions = accounts.sessions
    phone, csrf = sessions.start(user['user_id'])
    laptop, _ = sessions.start(user['user_id'])
    assert phone != laptop and csrf
    assert sessions.read(phone) == {'user': user, 'csrf': csrf}
    sessions.end(phone)
    assert sessions.read(phone) is None and sessions.read(laptop)
    kept, _ = sessions.start(user['user_id'])
    sessions.end_all(user['user_id'], keep=kept)
    assert sessions.read(laptop) is None and sessions.read(kept)
    sessions.end_all(user['user_id'])
    assert sessions.read(kept) is None


def test_an_unknown_or_malformed_cookie_names_nobody(accounts):
    accounts.users.create('athlete', PASSWORD)
    for value in ('', 'made-up', None, 42):
        assert accounts.sessions.read(value) is None
