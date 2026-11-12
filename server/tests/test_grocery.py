import math
import copy
from datetime import date

import pytest

from euros_server.domain import DomainError, Store
from euros_server.grocery import grocery_list
from euros_server.meal_week import MealWeeks

TODAY = date(2026, 11, 9)
WEEK = '2026-11-16'


@pytest.fixture
def store(tmp_path):
    return Store(tmp_path / 'db')


@pytest.fixture
def weeks(store):
    return MealWeeks(store)


def applied(store, weeks, **kwargs):
    draft = weeks.draft(WEEK, today=TODAY, **kwargs)
    weeks.apply(draft['draft_id'])
    return draft


def test_aggregates_saved_week_in_purchase_form(store, weeks):
    applied(store, weeks)
    result = grocery_list(store, weeks.catalog, WEEK)
    assert result['missing_dates'] == [] and len(result['planned_dates']) == 7
    assert result['unresolved'] == [] and result['use_up_meals'] == []
    foods = weeks.catalog.snapshot()['foods']
    plans = store.meal_plans(WEEK, '2026-11-22')['records']
    totals = {}
    for r in plans:
        for m in r['payload']['meals']:
            for i in m['items']:
                totals[i['food_id']] = totals.get(i['food_id'], 0) + i['grams']
    by_id = {i['food_id']: i for i in result['items']}
    assert set(by_id) == set(totals)
    for food_id, item in by_id.items():
        food = foods[food_id]
        raw = totals[food_id] / food['purchase'].get('cooked_yield', 1)
        assert item['buy_g'] >= raw > 0  # never negative, never under-buys
        assert item['buy_g'] % max(1, int(food['step_g'])) == 0
        assert item['planned_g'] == round(totals[food_id], 1)
    chicken = by_id['chicken']
    assert chicken['buy_form'] == 'raw boneless chicken breast'
    assert chicken['buy_g'] == math.ceil(totals['chicken'] / .75 / 5) * 5
    oil = by_id['oil']  # as-sold foods pass through unconverted
    assert oil['buy_form'] == 'olive oil' and oil['buy_g'] == math.ceil(totals['oil'] / 5) * 5


def test_missing_days_reported_not_invented(store, weeks):
    weeks.apply(weeks.draft(WEEK, dates=[WEEK], today=TODAY)['draft_id'])
    result = grocery_list(store, weeks.catalog, WEEK)
    assert result['planned_dates'] == [WEEK] and len(result['missing_dates']) == 6
    empty = grocery_list(store, weeks.catalog, '2026-12-07')
    assert empty['items'] == [] and len(empty['missing_dates']) == 7


def test_use_up_meals_excluded_and_unconvertible_unresolved(store, weeks):
    applied(store, weeks, swaps=[dict(date='2026-11-20', meal_id='dinner', template='ribs_use_up')])
    catalog = weeks.catalog.snapshot()
    shrimp = {k: v for k, v in catalog['foods']['shrimp'].items() if k != 'revision'}
    shrimp['purchase'] = {}  # cooked basis with no yield: cannot be converted
    weeks.catalog.upsert('food', 'shrimp', shrimp, catalog['foods']['shrimp']['revision'])
    result = grocery_list(store, weeks.catalog, WEEK)
    assert result['use_up_meals'] == [dict(date='2026-11-20', meal_id='dinner', title='Rib meat + sweet potato')]
    assert 'ribs' not in {i['food_id'] for i in result['items']}
    assert [u['food_id'] for u in result['unresolved']] == ['shrimp']
    assert result['unresolved'][0]['planned_g'] > 0 and 'cooked_yield' in result['unresolved'][0]['reason']


def test_week_start_must_be_monday(store, weeks):
    with pytest.raises(DomainError):
        grocery_list(store, weeks.catalog, '2026-11-17')


def save_plan(store, payload, revision=0):
    return store.command(dict(command_id=f"plan-{payload['date']}-{revision}", entity_type='meal_plans',
                              entity_id='meal-plan-' + payload['date'], expected_revision=revision,
                              operation='upsert', payload=payload))


def small_plan(day, grams=100):
    meal = dict(id='dinner', title='Chicken', foods=[f'{grams} g cooked chicken'], appliesWhen='every_day',
                kcal=165, proteinG=31, carbohydrateG=0, fatG=3.6,
                items=[dict(food_id='chicken', grams=grams)])
    return dict(date=day, reference_day='recovery',
                nutrition_targets=dict(kcal=1850, proteinG=120, carbohydrateG=165, fatG=72), meals=[meal])


def qualify_for_maintenance(store):
    for d in range(16, 23):
        day=f'2026-11-{d}'
        store.command(dict(command_id=day, entity_type='weight_entries', entity_id=day, expected_revision=0,
                           operation='upsert', payload=dict(local_date=day, weight_lb=155,
                                                           measured_at=day+'T08:00:00-06:00')))


def test_effective_maintenance_portions_follow_each_date_and_corrections(store, weeks):
    qualify_for_maintenance(store)
    for day in ('2026-11-21', '2026-11-22'):
        plan=small_plan(day)
        plan['maintenance_meals']=[copy.deepcopy(small_plan(day, 200)['meals'][0])]
        save_plan(store, plan)
    before=store.records()
    result=grocery_list(store, weeks.catalog, WEEK, today='2026-11-22')
    # The qualifying day gets maintenance; earlier dates keep their original portions.
    assert result['items'][0]['planned_g']==300
    assert result['unresolved']==[] and store.records()==before
    future=grocery_list(store, weeks.catalog, WEEK, today='2026-11-21')
    assert future['items'][0]['planned_g']==200  # future reading cannot qualify yet
    store.command(dict(command_id='delete-weight', entity_type='weight_entries', entity_id='2026-11-20',
                       expected_revision=1, operation='delete', payload={}))
    corrected=grocery_list(store, weeks.catalog, WEEK, today='2026-11-22')
    assert corrected['items'][0]['planned_g']==200


def test_text_only_meals_are_unresolved_instead_of_silently_omitted(store, weeks):
    plan=small_plan(WEEK)
    legacy=copy.deepcopy(plan['meals'][0]);legacy.update(id='lunch',title='Legacy lunch',foods=['One sandwich'])
    legacy.pop('items')
    plan['meals'].append(legacy)
    save_plan(store,plan)
    before=store.records()
    result=grocery_list(store, weeks.catalog, WEEK)
    assert result['items'][0]['planned_g']==100
    missing=result['unresolved'][0]
    assert missing['date']==WEEK and missing['meal_id']=='lunch'
    assert missing['foods']==['One sandwich'] and 'structured' in missing['reason']
    assert store.records()==before


def test_missing_maintenance_variant_is_reported_without_guessing(store, weeks):
    qualify_for_maintenance(store)
    save_plan(store,small_plan('2026-11-22'))
    result=grocery_list(store, weeks.catalog, WEEK, today='2026-11-22')
    assert result['items']==[] and 'maintenance' in result['unresolved'][0]['reason']
