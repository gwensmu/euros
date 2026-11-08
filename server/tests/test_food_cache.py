import asyncio
from copy import deepcopy

import pytest
from starlette.testclient import TestClient
from euros_server.app import create_app
from euros_server.domain import DomainError, Store
from euros_server.food_cache import FoodCache
from euros_server.llm import ModelService

DRAFT = {'nutrients': {'kcal': '50.1', 'protein_g': '10', 'carbs_g': '2', 'fat_g': '0'}, 'quantity_g': '100', 'clarification': None,
         'interpretation': {'provider': 'test', 'model': 'fixture', 'prompt_version': 'one'}}

class Models:
    def __init__(self): self.calls = []; self.fail = set(); self.unknown = set()
    async def food(self, body):
        text = body['text']; self.calls.append(text)
        await asyncio.sleep(0.001)
        if text in self.fail: raise DomainError('model_timeout', 504)
        result = deepcopy(DRAFT); result['raw_text'] = text
        if text in self.unknown:
            result['nutrients']['kcal'] = None; result['clarification'] = 'Which food?'
        return result

@pytest.fixture
def setup(tmp_path):
    store = Store(tmp_path/'db'); models = Models()
    return store, models, FoodCache(store, models)

def test_components_reuse_restart_exact_keys_and_decimal_totals(setup):
    store, models, cache = setup
    async def run():
        r = await cache.food({'text': '100g yogurt, 10g chicken'})
        assert r['nutrients']['kcal'] == '100.2' and r['quantity_g'] == '200'
        assert r['raw_text'] == '100g yogurt, 10g chicken'
        assert len(models.calls) == 2
        restarted = FoodCache(Store(store.path), models)
        await restarted.food({'text': ' 100g yogurt , 20g oats '})
        assert models.calls == ['100g yogurt', '10g chicken', '20g oats']
        await restarted.food({'text': '200g yogurt'})
        assert models.calls[-1] == '200g yogurt'
        assert store.records() == {'records': []} and store.changes()['changes'] == []
    asyncio.run(run())

def test_duplicate_items_count_twice_but_query_once_and_concurrent_requests_coalesce(setup):
    _, models, cache = setup
    async def run():
        a, b = await asyncio.gather(cache.food({'text': 'yogurt,yogurt'}), cache.food({'text': 'yogurt'}))
        assert a['nutrients']['kcal'] == '100.2' and b['nutrients']['kcal'] == '50.1'
        assert models.calls == ['yogurt']
    asyncio.run(run())

def test_failed_item_retries_without_repeating_successful_sibling(setup):
    _, models, cache = setup
    async def run():
        models.fail.add('chicken')
        with pytest.raises(DomainError): await cache.food({'text': 'yogurt,chicken'})
        models.fail.clear()
        await cache.food({'text': 'yogurt,chicken'})
        assert models.calls.count('yogurt') == 1 and models.calls.count('chicken') == 2
    asyncio.run(run())

def test_clarification_never_caches_or_returns_a_partial_meal_as_complete(setup):
    _, models, cache = setup
    async def run():
        models.unknown.add('mystery')
        r = await cache.food({'text': 'yogurt,mystery'})
        assert all(v is None for v in r['nutrients'].values()) and r['clarification']
        await cache.food({'text': 'yogurt,mystery'})
        assert models.calls.count('yogurt') == 1 and models.calls.count('mystery') == 2
    asyncio.run(run())

def test_explicit_whole_entry_weight_keeps_context_and_unknown_mass_stays_unknown(setup):
    _, models, cache = setup
    text = 'yogurt, chicken\nTotal weight of this food entry: 150 g. Use this total weight when calculating the nutrients.'
    asyncio.run(cache.food({'text': text}))
    assert models.calls == [text]

@pytest.mark.parametrize('text', ['', ' , , ', 'x'*8001, ','.join(['x']*31)])
def test_invalid_inputs_never_call_model(setup, text):
    _, models, cache = setup
    with pytest.raises(DomainError): asyncio.run(cache.food({'text': text}))
    assert models.calls == []


def test_http_repeat_bypasses_provider_budget_and_keeps_logging_separate(tmp_path):
    class Provider:
        calls = 0
        async def generate(self, **kwargs):
            self.calls += 1
            return {k: deepcopy(DRAFT[k]) for k in ('nutrients','quantity_g','clarification')}
    store = Store(tmp_path/'db'); provider = Provider()
    with TestClient(create_app(store,'a'*40,'b'*40,model_service=ModelService(provider)),base_url='http://localhost') as client:
        for _ in range(8):
            r = client.post('/v1/interpret/food', headers={'Authorization':'Bearer '+'a'*40}, json={'text':'100g yogurt,10g chicken'})
            assert r.status_code == 200, r.text
        r = client.post('/mcp', headers={'Authorization':'Bearer '+'b'*40,'Accept':'application/json, text/event-stream'}, json={'jsonrpc':'2.0','id':1,'method':'tools/call','params':{'name':'interpret_food','arguments':{'text':'100g yogurt,10g chicken'}}})
        assert r.status_code == 200 and not r.json()['result'].get('isError')
        assert provider.calls == 2
        with store.connect() as db: assert db.execute('SELECT COUNT(*) FROM inference_requests').fetchone()[0] == 2
        assert store.records() == {'records': []}


def test_unknown_mass_does_not_block_known_nutrition_cache(setup):
    _, models, cache = setup
    original = models.food
    async def food(body):
        result = await original(body)
        result['quantity_g'] = None
        return result
    models.food = food
    async def run():
        result = await cache.food({'text':'egg,toast'})
        assert result['quantity_g'] is None and result['nutrients']['kcal'] == '100.2'
        await cache.food({'text':'egg,toast'})
        assert len(models.calls) == 2
    asyncio.run(run())
