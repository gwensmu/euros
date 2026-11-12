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
    staples = {f for f in totals if foods[f]['purchase'].get('staple')}
    assert staples and set(by_id) == set(totals) - staples
    assert {s['food_id'] for s in result['check_stock']} == staples
    for food_id, item in by_id.items():
        food = foods[food_id]
        raw = totals[food_id] / food['purchase'].get('cooked_yield', 1)
        assert item['buy_g'] >= raw > 0  # never negative, never under-buys
        assert item['buy_g'] % max(1, int(food['step_g'])) == 0
        assert item['planned_g'] == round(totals[food_id], 1)
    chicken = by_id['chicken']
    assert chicken['buy_form'] == 'raw boneless chicken breast'
    assert chicken['buy_g'] == math.ceil(totals['chicken'] / .75 / 5) * 5
    yogurt = by_id['yogurt']  # as-sold foods pass through unconverted
    assert yogurt['buy_form'] == 'plain Greek yogurt' and yogurt['buy_g'] == math.ceil(totals['yogurt'] / 5) * 5


def test_quantities_are_countable_store_units(store, weeks):
    applied(store, weeks)
    result = grocery_list(store, weeks.catalog, WEEK)
    foods = weeks.catalog.snapshot()['foods']
    by_id = {i['food_id']: i for i in result['items']}
    for food_id, item in by_id.items():
        unit = foods[food_id]['purchase'].get('unit')
        if not unit:  # sold loose: a weight line, never a guessed count
            assert 'buy_count' not in item and 'by weight' in item['buy_text']
            continue
        per_pack = (unit.get('pack') or {}).get('count', 1)
        needed = math.ceil(item['buy_g'] / unit['grams'])
        assert item['buy_count'] == math.ceil(max(needed, per_pack) / per_pack) * per_pack  # up, never short
        assert item['buy_unit_total_g'] >= item['buy_g']
        assert item["buy_unit"] in item["buy_text"] and item["buy_text"].startswith(str(item["buy_count"]))
        assert item['buy_unit'] == (unit['name'] if item['buy_count'] == 1 else unit['plural'])
    eggs, bananas = by_id['eggs'], by_id['banana']
    assert eggs['buy_count'] % 12 == 0 and eggs['buy_unit'] == 'eggs'
    assert bananas['buy_count'] == math.ceil(bananas['buy_g'] / 118) and bananas['buy_unit'] == 'bananas'


def test_counts_round_up_to_whole_packs(store, weeks):
    # 350 g of egg is seven eggs, but eggs come by the dozen.
    plan = small_plan(WEEK)
    plan['meals'][0]['items'] = [dict(food_id='eggs', grams=350)]
    save_plan(store, plan)
    item = grocery_list(store, weeks.catalog, WEEK)['items'][0]
    assert item['buy_count'] == 12 and item['buy_packs'] == 1 and item['buy_pack'] == 'dozen'
    assert item['buy_pack_count'] == 12 and item['buy_unit_total_g'] == 600
    assert item['buy_text'] == '12 eggs (1 dozen, 350 g needed)'
    assert item['buy_g'] == 350 and item['planned_g'] == 350  # the need itself is not inflated


def test_second_pack_opens_only_when_needed(store, weeks):
    plan = small_plan(WEEK)
    plan['meals'][0]['items'] = [dict(food_id='eggs', grams=600)]
    save_plan(store, plan)
    assert grocery_list(store, weeks.catalog, WEEK)['items'][0]['buy_text'] == '12 eggs (1 dozen, 600 g needed)'
    plan['meals'][0]['items'] = [dict(food_id='eggs', grams=650)]
    save_plan(store, plan, revision=1)
    item = grocery_list(store, weeks.catalog, WEEK)['items'][0]
    assert item['buy_count'] == 24 and item['buy_packs'] == 2
    assert item['buy_text'] == '24 eggs (2 dozen, 650 g needed)'


def test_pantry_staples_are_listed_to_check_without_amounts(store, weeks):
    applied(store, weeks)
    result = grocery_list(store, weeks.catalog, WEEK)
    stock = {s['food_id']: s for s in result['check_stock']}
    assert {'oil', 'rice'} <= set(stock) and not {'oil', 'rice'} & {i['food_id'] for i in result['items']}
    for s in stock.values():
        assert set(s) == {'food_id', 'name', 'buy_form', 'meals'}  # no grams, no counts
        assert s['meals'] > 0
    assert stock['rice']['buy_form'] == 'dry rice'


def test_staple_flag_is_editable_and_skips_conversion(store, weeks):
    save_plan(store, small_plan(WEEK))
    catalog = weeks.catalog.snapshot()
    chicken = {k: v for k, v in catalog['foods']['chicken'].items() if k != 'revision'}
    chicken['purchase'] = dict(staple=True)  # e.g. a freezer stockpile: no yield needed to just check it
    weeks.catalog.upsert('food', 'chicken', chicken, catalog['foods']['chicken']['revision'])
    result = grocery_list(store, weeks.catalog, WEEK)
    assert result['items'] == [] and result['unresolved'] == []
    assert [s['food_id'] for s in result['check_stock']] == ['chicken']


def test_single_unit_uses_singular_label(store, weeks):
    save_plan(store, small_plan(WEEK, 40))
    catalog = weeks.catalog.snapshot()
    chicken = {k: v for k, v in catalog['foods']['chicken'].items() if k != 'revision'}
    chicken['purchase'] = dict(form='raw boneless chicken breast', cooked_yield=.75,
                               unit=dict(name='1 lb pack of chicken breast', grams=454))
    weeks.catalog.upsert('food', 'chicken', chicken, catalog['foods']['chicken']['revision'])
    item = grocery_list(store, weeks.catalog, WEEK)['items'][0]
    assert item['buy_count'] == 1 and item['buy_unit'] == '1 lb pack of chicken breast'
    assert item['buy_text'] == '1 x 1 lb pack of chicken breast (55 g needed)'


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




def test_existing_catalog_backfills_pack_sizes_without_touching_edits(store, weeks):
    from euros_server.catalog import Catalog
    from euros_server.domain import canonical
    snapshot = weeks.catalog.snapshot()['foods']
    # Simulate a database seeded before purchase units existed: unit stripped at revision 1.
    with store.connect() as db:
        for food_id in ('banana', 'eggs', 'apple', 'oil'):
            old = {k: v for k, v in snapshot[food_id].items() if k != 'revision'}
            old['purchase'] = {k: v for k, v in old['purchase'].items() if k not in ('unit', 'staple')}
            if food_id == 'apple':
                old['name'] = 'honeycrisp apple'  # an owner edit that must survive untouched
            db.execute("UPDATE catalog_items SET revision=1, payload=? WHERE kind='food' AND id=?", (canonical(old), food_id))
            db.execute("DELETE FROM catalog_history WHERE kind='food' AND id=?", (food_id,))
    foods = Catalog(store).snapshot()['foods']
    assert foods['banana']['purchase']['unit']['grams'] == 118 and foods['banana']['revision'] == 2
    assert foods['eggs']['purchase']['unit']['name'] == 'egg'
    assert foods['oil']['purchase']['staple'] is True and foods['oil']['revision'] == 2
    assert foods['apple']['name'] == 'honeycrisp apple' and 'unit' not in foods['apple']['purchase']
    assert foods['apple']['revision'] == 1
    again = Catalog(store).snapshot()['foods']  # idempotent: no revision churn on restart
    assert again['banana']['revision'] == 2 and again['apple']['revision'] == 1


@pytest.mark.parametrize('item', [dict(food_id='chicken'), dict(food_id='chicken', grams=0), dict(food_id='chicken', grams='lots'),
                                  dict(grams=100), dict(food_id='chicken', grams=100, per100g=dict(kcal=1)),
                                  dict(food_id='chicken', grams=100, food_revision=0), 'chicken'])
def test_unusable_meal_items_are_rejected_at_save(store, weeks, item):
    plan = small_plan(WEEK)
    plan['meals'][0]['items'] = [item]
    with pytest.raises(DomainError):
        save_plan(store, plan)
    assert grocery_list(store, weeks.catalog, WEEK)['planned_dates'] == []


def test_retired_maintenance_meals_are_ignored(store, weeks):
    # Plans saved before the weight phase became a target-only concept may still carry a
    # second set of portions; shopping follows the plan's one set of meals regardless.
    plan = small_plan(WEEK)
    plan['maintenance_meals'] = [dict(small_plan(WEEK, 200)['meals'][0])]
    save_plan(store, plan)
    result = grocery_list(store, weeks.catalog, WEEK)
    assert result['items'][0]['planned_g'] == 100 and result['unresolved'] == []


def test_annotated_items_keep_extra_fields(store):
    plan = small_plan(WEEK)
    plan['meals'][0]['items'][0]['note'] = 'thighs ok'
    saved = save_plan(store, plan)['record']
    assert saved['payload']['meals'][0]['items'][0]['note'] == 'thighs ok'


def test_later_template_edits_do_not_change_saved_plans(store, weeks):
    applied(store, weeks)
    before = grocery_list(store, weeks.catalog, WEEK)
    plans = store.meal_plans(WEEK, '2026-11-22')['records']
    assert all(m['use_up'] is False and isinstance(m['batch'], bool) for r in plans for m in r['payload']['meals'])
    t = weeks.catalog.snapshot()['templates']['chicken_rice']
    weeks.catalog.upsert('template', 'chicken_rice', {**{k: v for k, v in t.items() if k != 'revision'}, 'use_up': True},
                         t['revision'])
    after = grocery_list(store, weeks.catalog, WEEK)
    assert after['use_up_meals'] == before['use_up_meals'] == [] and after['items'] == before['items']


def test_legacy_meals_without_flags_fall_back_to_the_template(store, weeks):
    plan = small_plan(WEEK)
    plan['meals'][0]['template_id'] = 'ribs_use_up'  # saved before meals carried use_up
    save_plan(store, plan)
    result = grocery_list(store, weeks.catalog, WEEK)
    assert result['items'] == [] and result['use_up_meals'][0]['meal_id'] == 'dinner'


def test_built_ins_are_stored_in_validated_form(weeks):
    from euros_server.catalog import validate_food, validate_template
    snapshot = weeks.catalog.snapshot()
    for food in snapshot['foods'].values():
        payload = {k: v for k, v in food.items() if k != 'revision'}
        assert validate_food(payload) == payload
    for template in snapshot['templates'].values():
        payload = {k: v for k, v in template.items() if k != 'revision'}
        assert validate_template(payload, snapshot['foods']) == payload
    assert snapshot['foods']['bread']['purchase']['unit']['plural'] == 'loaves of multigrain bread'
    assert snapshot['foods']['banana']['purchase']['unit']['plural'] == 'bananas'  # default filled in
