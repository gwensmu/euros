"""What a browser presents: the origin a request came from."""
from starlette.requests import Request

from euros_server.browser import base_url, same_origin


def request(headers=None, scheme='http', host='localhost'):
    raw = [(b'host', host.encode())] + [(k.encode(), v.encode()) for k, v in (headers or {}).items()]
    return Request({'type': 'http', 'method': 'POST', 'path': '/account/plan', 'scheme': scheme,
                    'server': (host, 80), 'query_string': b'', 'headers': raw})


def test_the_public_url_wins_over_the_hop_the_service_sees():
    proxied = request({'x-forwarded-proto': 'https'}, host='euros.example')
    assert base_url(proxied) == 'https://euros.example'
    assert base_url(proxied, 'https://euros.example/') == 'https://euros.example'
    # A configured public URL describes the deployment, not this request.
    assert base_url(request(), 'https://euros.example') == 'https://euros.example'
    assert base_url(request()) == 'http://localhost'


def test_a_post_must_come_from_this_site():
    assert same_origin(request({'origin': 'http://localhost'}))
    assert not same_origin(request({'origin': 'https://evil.example'}))
    # Behind a terminator the browser's origin is the public one.
    proxied = {'x-forwarded-proto': 'https', 'origin': 'https://euros.example'}
    assert same_origin(request(proxied, host='euros.example'), 'https://euros.example')
    assert not same_origin(request({**proxied, 'origin': 'https://evil.example'}, host='euros.example'),
                           'https://euros.example')


def test_a_referer_stands_in_for_a_missing_origin():
    assert same_origin(request({'referer': 'http://localhost/account/plan'}))
    assert not same_origin(request({'referer': 'https://evil.example/attack'}))
    # No origin and no referer is a client that names no site, such as a command-line tool.
    assert same_origin(request())
