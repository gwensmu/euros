"""Removal is enforced at the public surfaces, without deleting historical data."""
from starlette.testclient import TestClient
from euros_server.app import create_app
from euros_server.domain import Store
from euros_server.llm import ModelService


def test_retired_routes_and_tool_cannot_generate_or_remove_saved_data(tmp_path):
    class NoCalls:
        async def generate(self, **kwargs):
            raise AssertionError('Retired feature must never call a model')
    store = Store(tmp_path / 'db')
    with store.connect() as db:
        db.execute('CREATE TABLE review_jobs (id TEXT PRIMARY KEY, state TEXT, result TEXT)')
        db.execute("INSERT INTO review_jobs VALUES ('old', 'queued', 'retained')")
    with TestClient(create_app(store, 'a'*40, 'b'*40, model_service=ModelService(NoCalls())), base_url='http://localhost') as client:
        for path in ('/v1/review/week', '/v1/review/jobs', '/v1/review/jobs/old'):
            response = client.request('GET' if path.endswith('/old') else 'POST', path,
                                      headers={'Authorization':'Bearer '+'a'*40}, json={})
            assert response.status_code == 404
        headers = {'Authorization':'Bearer '+'b'*40, 'Accept':'application/json, text/event-stream'}
        response = client.post('/mcp', headers=headers, json={'jsonrpc':'2.0','id':1,'method':'tools/list','params':{}})
        tools = {tool['name'] for tool in response.json()['result']['tools']}
        assert 'review_week' not in tools and 'interpret_food' in tools
    with store.connect() as db:
        assert tuple(db.execute('SELECT * FROM review_jobs').fetchone()) == ('old', 'queued', 'retained')
    assert store.records() == {'records':[]}
