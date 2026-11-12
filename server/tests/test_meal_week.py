import copy
import json
from datetime import date

import pytest
from starlette.testclient import TestClient

from euros_server.app import create_app
from euros_server.domain import DomainError, Store
from euros_server.meal_planning import preview
from euros_server.meal_week import MealWeeks

TODAY = date(2026, 11, 9)
WEEK = '2026-11-16'


@pytest.fixture
def store(tmp_path):
    return Store(tmp_path / 'db')


@pytest.fixture
def weeks(store):
    return MealWeeks(store)


def bjj(store, when, identity='bjj-1'):
    return store.command(dict(command_id='c-' + identity, entity_type='activities', entity_id=identity, expected_revision=0,
                              operation='upsert', payload=dict(kind='bjj', title='BJJ', status='pending', scheduled_at=when)))


def test_confirmed_extension_keeps_rest_days_and_moved_workouts(store, weeks):
    # Synthetic accepted week: Tue/Thu/Sat BJJ, Sun weights, with Monday off.
    for day, clock in [('17', '18:30'), ('19', '18:30'), ('21', '10:00')]:
        bjj(store, f'2026-11-{day}T{clock}:00-06:00', 'class-' + day)
    store.command(dict(command_id='weights', entity_type='activities', entity_id='weights', expected_revision=0,
                       operation='upsert', payload=dict(kind='weights', title='Weights', status='pending',
                                                       scheduled_at='2026-11-22T11:00:00-06:00')))
    before = store.records()
    draft = weeks.draft(WEEK, today=TODAY)
    assert store.records() == before
    assert [d['tier'] for d in draft['days']] == ['recovery', 'bjj', 'recovery', 'bjj', 'recovery', 'bjj', 'lifting']
    monday = draft['days'][0]
    assert monday['targets']['kcal'] == 1850 and monday['training'] == []
    assert not any(m['id'] == 'pre-training' for m in monday['meals'])
    assert all(d['training_basis'] == 'saved-schedule' for d in draft['days'])
    current = next(r for r in store.records('activities')['records'] if r['entity_id'] == 'class-17')
    moved = {**current['payload'], 'scheduled_at': '2026-11-16T18:30:00-06:00'}
    store.command(dict(command_id='move', entity_type='activities', entity_id=current['entity_id'],
                       expected_revision=1, operation='upsert', payload=moved))
    redraft = weeks.draft(WEEK, today=TODAY)
    assert [d['tier'] for d in redraft['days'][:2]] == ['bjj', 'recovery']
    current = next(r for r in store.records('activities')['records'] if r['entity_id'] == 'class-19')
    store.command(dict(command_id='skip', entity_type='activities', entity_id=current['entity_id'],
                       expected_revision=1, operation='upsert', payload={**current['payload'], 'status': 'skipped'}))
    skipped = weeks.draft(WEEK, today=TODAY)['days'][3]
    assert skipped['tier'] == 'bjj' and skipped['targets']['kcal'] == 2300


def test_confirmed_taper_has_no_recurring_weights_or_extra_bjj(store, weeks):
    bjj(store, '2027-01-05T18:30:00-06:00', 'taper-1')
    bjj(store, '2027-01-07T18:30:00-06:00', 'taper-2')
    draft = weeks.draft('2027-01-04', today=TODAY)
    assert [d['tier'] for d in draft['days']] == ['recovery', 'bjj', 'recovery', 'bjj', 'recovery', 'recovery', 'recovery']
    assert all(d['training_basis'] == 'saved-schedule' for d in draft['days'])
    boundary = weeks.draft('2027-01-11', today=TODAY)
    assert all(d['training_basis'] == 'saved-schedule' and d['tier'] == 'recovery' for d in boundary['days'][:2])
    assert all(d['training_basis'] == 'provisional-weekly-pattern' for d in boundary['days'][2:])
    bjj(store, '2027-01-13T14:00:00-06:00', 'dated-after-horizon')
    saved = weeks.draft('2027-01-11', today=TODAY)['days'][2]
    assert saved['training_basis'] == 'saved-schedule' and saved['training'] == ['bjj 14:00']


def test_draft_saves_nothing_and_meets_policy(store, weeks):
    draft = weeks.draft('2027-01-18', today=TODAY)
    assert draft['saved'] is False and len(draft['days']) == 7
    assert store.records()['records'] == []
    for day in draft['days']:
        assert day['training_basis'] == 'provisional-weekly-pattern'
        assert abs(day['planned_totals']['kcal'] - day['targets']['kcal']) < 200
        assert day['planned_totals']['proteinG'] >= 129
    assert [d['tier'] for d in draft['days']] == ['recovery', 'bjj', 'recovery', 'bjj', 'recovery', 'bjj', 'lifting']
    # Wed and Sun only, and only when batch meals fall in the window.
    assert {p['date'] for p in draft['preps']} <= {'2027-01-20', '2027-01-24'} and '2027-01-20' in {p['date'] for p in draft['preps']}
    wed = next(p for p in draft['preps'] if p['date'] == '2027-01-20')
    assert 'portion' in wed['instructions'] and 2 <= len(wed['covers']) <= 4
    times = {f"{d['date']} {m['id']}": m['time'] for d in draft['days'] for m in d['meals']}
    assert all(times[c] >= wed['time'] for c in wed['covers'] if c.startswith('2027-01-20'))


def test_apply_week_atomic_idempotent_and_never_logs_food(store, weeks):
    draft = weeks.draft(WEEK, today=TODAY)
    applied = weeks.apply(draft['draft_id'])
    assert len(applied['meal_plans']) == 7 and all(p['revision'] == 1 for p in applied['meal_plans'])
    assert weeks.apply(draft['draft_id']) == applied
    assert store.records('food_entries')['records'] == []
    saved = store.meal_plan('2026-11-19')['record']['payload']
    preview(saved)
    assert saved['maintenance_meals'] and saved['training_basis'] == 'saved-schedule'
    preps = [r for r in store.records('activities')['records'] if r['payload']['kind'] == 'prep']
    assert {r['entity_id'] for r in preps} == {f"prep-{p['date']}" for p in draft['preps']}
    assert all(r['payload']['status'] == 'pending' and r['payload']['covered_meals'] for r in preps)


def test_stale_draft_saves_nothing(store, weeks):
    first = weeks.draft(WEEK, today=TODAY)
    weeks.apply(first['draft_id'])
    redraft = weeks.draft(WEEK, swaps=[dict(date='2026-11-20', meal_id='dinner', template='salmon_potato')], today=TODAY)
    # Someone edits Saturday after the redraft.
    sat = store.meal_plan('2026-11-21')['record']
    edited = copy.deepcopy(sat['payload']); edited['meals'][0]['title'] = 'Edited'
    store.command(dict(command_id='edit-sat', entity_type='meal_plans', entity_id=sat['entity_id'],
                       expected_revision=1, operation='upsert', payload=edited))
    with pytest.raises(DomainError) as exc:
        weeks.apply(redraft['draft_id'])
    assert exc.value.body['error'] == 'draft_stale'
    fri = store.meal_plan('2026-11-20')['record']
    assert fri['revision'] == 1 and not any(m['template_id'] == 'salmon_potato' for m in fri['payload']['meals'])


def test_swaps_avoid_foods_and_replaces(store, weeks):
    bjj(store, '2026-11-20T18:30:00-06:00')
    weeks.apply(weeks.draft(WEEK, today=TODAY)['draft_id'])
    draft = weeks.draft(WEEK, swaps=[dict(date='2026-11-20', meal_id='dinner', template='salmon_potato')],
                        avoid_foods=['eggs', 'bread'], today=TODAY)
    meals = [m for d in draft['days'] for m in d['meals']]
    assert not any('egg' in f or 'bread' in f for m in meals for f in m['foods'])
    fri = next(d for d in draft['days'] if d['date'] == '2026-11-20')
    dinner = next(m for m in fri['meals'] if m['id'] == 'dinner')
    assert dinner['template'] == 'salmon_potato' and dinner['replaces'] not in (None, dinner['title'])
    # Pre-training snack keeps the banana when bread is avoided and no alternative exists.
    assert any('pre-training' in w for w in draft['warnings'])
    assert all(d['status'] == 'update' for d in draft['days'])


def test_saved_training_today_and_tombstones(store, weeks):
    bjj(store, '2026-11-12T20:30:00-06:00')
    store.command(dict(command_id='p', entity_type='meal_plans', entity_id='meal-plan-2026-11-14', expected_revision=0,
                       operation='upsert', payload=dict(date='2026-11-14', reference_day='x', meals=[dict(id='a', title='A', foods=[], appliesWhen='every_day')],
                                                         nutrition_targets=dict(kcal=1, proteinG=1, carbohydrateG=1, fatG=1))))
    store.command(dict(command_id='d', entity_type='meal_plans', entity_id='meal-plan-2026-11-14', expected_revision=1,
                       operation='delete', payload={}))
    draft = weeks.draft('2026-11-09', today=TODAY)
    assert [s['date'] for s in draft['skipped']] == ['2026-11-09', '2026-11-14']
    thu = next(d for d in draft['days'] if d['date'] == '2026-11-12')
    assert thu['training'] == ['bjj 20:30'] and thu['training_basis'] == 'saved-schedule'
    dinner = next(m for m in thu['meals'] if m['id'] == 'dinner')
    assert dinner['time'] == '17:30'
    wed = next(d for d in draft['days'] if d['date'] == '2026-11-11')
    assert wed['tier'] == 'recovery'


def test_done_prep_is_kept(store, weeks):
    store.command(dict(command_id='prep', entity_type='activities', entity_id='prep-v2-2026-11-18', expected_revision=0,
                       operation='upsert', payload=dict(kind='prep', title='Prep', status='done',
                                                        scheduled_at='2026-11-18T16:30:00-06:00')))
    draft = weeks.draft(WEEK, today=TODAY)
    assert {'date': '2026-11-18', 'status': 'kept (done)'} in draft['preps']
    weeks.apply(draft['draft_id'])
    assert store.records('activities')['records'][0]['revision'] == 1


@pytest.mark.parametrize('kwargs,error', [
    (dict(week_start='2026-11-17'), 'week_start_must_be_monday'),
    (dict(week_start=WEEK, dates=['2026-11-30']), 'dates_outside_week'),
    (dict(week_start=WEEK, avoid_foods=['tofu']), 'unknown_food'),
    (dict(week_start=WEEK, swaps=[dict(date='2026-11-20', meal_id='dinner', template='pizza')]), 'invalid_swap_template'),
    (dict(week_start=WEEK, swaps=[dict(date='2026-11-20', meal_id='brunch', template='oats')]), 'invalid_swap_meal'),
    (dict(week_start=WEEK, swaps=[dict(date='2026-11-20', meal_id='dinner', template='oats')]), 'template_not_for_meal'),
    (dict(week_start='2026-11-02'), 'nothing_to_draft'),
])
def test_invalid_requests(weeks, kwargs, error):
    with pytest.raises(DomainError) as exc:
        weeks.draft(today=TODAY, **kwargs)
    assert exc.value.body['error'] == error


def test_mcp_draft_and_apply(store):
    token = 'm' * 40
    with TestClient(create_app(store, 'a' * 40, token), base_url='http://localhost') as client:
        headers = {'Authorization': f'Bearer {token}', 'Accept': 'application/json, text/event-stream'}
        def call(name, args, id):
            r = client.post('/mcp', headers=headers, json={'jsonrpc': '2.0', 'id': id, 'method': 'tools/call',
                                                          'params': {'name': name, 'arguments': args}}).json()['result']
            return r, r.get('structuredContent') or json.loads(r['content'][0]['text'])
        client.post('/mcp', headers=headers, json={'jsonrpc': '2.0', 'id': 0, 'method': 'initialize', 'params': {
            'protocolVersion': '2025-03-26', 'capabilities': {}, 'clientInfo': {'name': 't', 'version': '1'}}})
        result, draft = call('draft_week', {'week_start': '2030-01-07'}, 1)
        assert not result.get('isError') and draft['saved'] is False
        result, applied = call('apply_week', {'draft_id': draft['draft_id']}, 2)
        assert not result.get('isError') and applied['saved'] is True
        result, missing = call('apply_week', {'draft_id': 'nope'}, 3)
        assert result['isError'] and missing['error'] == 'draft_not_found'


def macro_gaps(day):
    t, p = day['targets'], day['planned_totals']
    return {k: abs(p[k] - t[k]) for k in ('kcal', 'proteinG', 'carbohydrateG', 'fatG')}


def test_portions_balance_every_tier_including_maintenance(store, weeks):
    bjj(store, '2026-11-17T18:30:00-06:00', 'single')
    bjj(store, '2026-11-19T18:30:00-06:00', 'double')
    for identity, when in [('second', '2026-11-19T11:00:00-06:00'), ('lifting', '2026-11-22T11:00:00-06:00')]:
        store.command(dict(command_id=identity, entity_type='activities', entity_id=identity, expected_revision=0,
                           operation='upsert', payload=dict(kind='weights', title='Weights', status='pending', scheduled_at=when)))
    tiers = set()
    for week in ('2026-11-16', '2026-11-23', '2026-11-30'):
        draft = weeks.draft(week, today=TODAY)
        for day in draft['days']:
            tiers.add(day['tier'])
            gaps = macro_gaps(day)
            # Previously protein overshot by ~40 g; now every macro lands close to its tier.
            assert gaps['kcal'] <= 40 and gaps['proteinG'] <= 15 and gaps['carbohydrateG'] <= 15 and gaps['fatG'] <= 12, (day['date'], gaps)
    assert tiers == {'recovery', 'bjj', 'lifting', 'double'}
    weeks.apply(draft['draft_id'])
    saved = store.meal_plan('2026-12-03')['record']['payload']
    target, maintenance = saved['nutrition_targets'], saved['maintenance_planned_totals']
    assert abs(maintenance['kcal'] - target['kcal'] - 200) <= 50
    assert abs(maintenance['proteinG'] - saved['planned_totals']['proteinG']) <= 15
    item = saved['meals'][0]['items'][0]
    assert {'food_id', 'grams', 'food_revision', 'per100g'} <= set(item) and 'min_g' not in item


def test_catalog_seed_never_overwrites_and_edits_are_revisioned(store, weeks):
    catalog = weeks.catalog
    chicken = catalog.snapshot()['foods']['chicken']
    edited = {k: v for k, v in chicken.items() if k != 'revision'}
    edited['per100g'] = {**edited['per100g'], 'proteinG': 32, 'kcal': 160}
    edited['source'] = 'Store brand label'
    assert catalog.upsert('food', 'chicken', edited, 1)['revision'] == 2
    with pytest.raises(DomainError) as exc:
        catalog.upsert('food', 'chicken', edited, 1)
    assert exc.value.body['error'] == 'revision_conflict'
    assert catalog.upsert('food', 'chicken', edited, 2)['changed'] is False
    MealWeeks(store)  # restart re-seeds only missing IDs
    assert catalog.snapshot()['foods']['chicken']['per100g']['proteinG'] == 32
    with store.connect() as db:
        assert db.execute("SELECT count(*) FROM catalog_history WHERE id='chicken'").fetchone()[0] == 2
    # Plans snapshot the revision and values used.
    weeks.apply(weeks.draft(WEEK, today=TODAY)['draft_id'])
    plans = store.meal_plans('2026-11-17', '2026-11-22')['records']
    items = [i for p in plans for m in p['payload']['meals'] for i in m['items'] if i['food_id'] == 'chicken']
    assert items and all(i['food_revision'] == 2 and i['per100g']['proteinG'] == 32 for i in items)


def test_switched_off_entries_leave_rotation_but_stay_stored(weeks):
    def drafted():
        return {m['template'] for d in weeks.draft(WEEK, today=TODAY)['days'] for m in d['meals']}
    assert 'chicken_sandwich' in drafted()
    hummus = weeks.catalog.snapshot()['foods']['hummus']
    weeks.catalog.upsert('food', 'hummus', {**{k: v for k, v in hummus.items() if k != 'revision'}, 'active': False}, hummus['revision'])
    assert 'chicken_sandwich' not in drafted()
    assert 'chicken_sandwich' in weeks.catalog.snapshot()['templates']
    with pytest.raises(DomainError) as exc:
        weeks.draft(WEEK, swaps=[dict(date='2026-11-20', meal_id='lunch', template='chicken_sandwich')], today=TODAY)
    assert exc.value.body['error'] == 'template_uses_inactive_food'


@pytest.mark.parametrize('kind,payload,error', [
    ('food', dict(name='x', per100g=dict(kcal=100, proteinG=1, carbohydrateG=1), role='protein'), 'invalid_catalog_field'),
    ('food', dict(name='x', per100g=dict(kcal=100, proteinG=1, carbohydrateG=1, fatG=1), role='dessert'), 'invalid_catalog_field'),
    ('food', dict(name='x', per100g=dict(kcal=100, proteinG=101, carbohydrateG=1, fatG=1), role='protein'), 'invalid_catalog_field'),
    ('template', dict(title='x', slots=['brunch'], items=[dict(food_id='oats', grams=50)]), 'invalid_catalog_field'),
    ('template', dict(title='x', slots=['breakfast'], items=[dict(food_id='tofu', grams=50)]), 'unknown_food'),
    ('template', dict(title='x', slots=['breakfast'], items=[dict(food_id='oats', grams=50, max_g=40)]), 'invalid_template_item'),
])
def test_catalog_validation(weeks, kind, payload, error):
    with pytest.raises(DomainError) as exc:
        weeks.catalog.upsert(kind, 'new_item', payload, 0)
    assert exc.value.body['error'] == error


def test_mcp_catalog_edit(store):
    token = 'm' * 40
    with TestClient(create_app(store, 'a' * 40, token), base_url='http://localhost') as client:
        headers = {'Authorization': f'Bearer {token}', 'Accept': 'application/json, text/event-stream'}
        def call(name, args, id):
            r = client.post('/mcp', headers=headers, json={'jsonrpc': '2.0', 'id': id, 'method': 'tools/call',
                                                          'params': {'name': name, 'arguments': args}}).json()['result']
            return r, r.get('structuredContent') or json.loads(r['content'][0]['text'])
        _, ctx = call('meal_planning_context', {}, 1)
        assert 'pb_oats' in ctx['templates']
        food = dict(name='skyr', per100g=dict(kcal=63, proteinG=11, carbohydrateG=4, fatG=.2), role='protein', basis='as_sold')
        result, saved = call('upsert_food', {'food_id': 'skyr', 'food': food, 'expected_revision': 0}, 2)
        assert not result.get('isError') and saved['revision'] == 1
        result, stale = call('upsert_food', {'food_id': 'skyr', 'food': food, 'expected_revision': 0}, 3)
        assert result['isError'] and stale['error'] == 'revision_conflict'
        template = dict(title='Skyr + berries', slots=['recovery-snack'], items=[dict(food_id='skyr', grams=200), dict(food_id='berries', grams=100)])
        result, saved = call('upsert_template', {'template_id': 'skyr_berries', 'template': template, 'expected_revision': 0}, 4)
        assert not result.get('isError') and saved['payload']['active'] is True
