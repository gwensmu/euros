"""The shared meal-plan vocabulary: weeks, saved meal helpers and the tier/maintenance policy."""
from datetime import date

import pytest

from euros_server.domain import DomainError
from euros_server.meals import food_grams, meal_flag, meal_totals, parse_week, snapshot_item
from euros_server.nutrition_policy import MAINTENANCE_ADD, TIERS, maintenance_targets, tier_for, training_on


def test_parse_week():
    start, week = parse_week('2026-11-16')
    assert start == date(2026, 11, 16) and len(week) == 7 and week[-1] == date(2026, 11, 22)
    assert parse_week('2026-11-16', ['2026-11-18', '2026-11-17'])[1] == [date(2026, 11, 17), date(2026, 11, 18)]
    for bad in ('2026-11-17', '2026-11-16T00:00', None, '2026-W47'):
        with pytest.raises(DomainError):
            parse_week(bad)
    for dates in ([], ['2026-11-23'], 'x', [1]):
        with pytest.raises(DomainError):
            parse_week('2026-11-16', dates)


def test_snapshot_item_records_the_food_values_used():
    food = dict(revision=3, per100g=dict(kcal=1, proteinG=1, carbohydrateG=1, fatG=1))
    assert snapshot_item('x', food, 40, min_g=10) == dict(food_id='x', grams=40, food_revision=3,
                                                           per100g=food['per100g'], min_g=10)


def test_meal_flag_prefers_the_saved_meal():
    templates = {'t': dict(use_up=True)}
    assert meal_flag(dict(template_id='t', use_up=False), templates, 'use_up') is False
    assert meal_flag(dict(template_id='t'), templates, 'use_up') is True
    assert meal_flag(dict(template_id='gone'), templates, 'use_up') is False


def test_meal_totals_count_gaps_instead_of_guessing():
    totals, missing = meal_totals([dict(kcal=100, proteinG=10.04), dict(kcal=50.5, proteinG=None, fatG=dict(min=1, max=2))])
    assert totals == dict(kcal=150.5, proteinG=10.0, carbohydrateG=0, fatG=0)
    assert missing == dict(kcal=0, proteinG=1, carbohydrateG=2, fatG=2)


def test_food_grams_counts_meals_per_food():
    meals = [dict(items=[dict(food_id='a', grams=10), dict(food_id='b', grams=5)]), dict(items=[dict(food_id='a', grams=20)]), dict()]
    assert food_grams(meals) == {'a': dict(grams=30, meals=2), 'b': dict(grams=5, meals=1)}
    assert food_grams(meals, include=lambda f: f == 'b') == {'b': dict(grams=5, meals=1)}


@pytest.mark.parametrize('kinds,tier', [([], 'recovery'), (['weights'], 'lifting'), (['bjj'], 'bjj'),
                                        (['weights', 'weights'], 'double'), (['bjj', 'weights'], 'double')])
def test_tier_for(kinds, tier):
    assert tier_for(kinds) == tier and tier_for(iter(kinds)) == tier


def test_maintenance_targets_add_only_energy_as_carbohydrate():
    base = TIERS['bjj']
    out = maintenance_targets(base)
    assert out['kcal'] == base['kcal'] + MAINTENANCE_ADD['kcal']
    assert out['carbohydrateG'] == base['carbohydrateG'] + MAINTENANCE_ADD['carbohydrateG']
    assert out['proteinG'] == base['proteinG'] and out['fatG'] == base['fatG'] and base == TIERS['bjj']


def test_training_on_uses_the_local_date_and_ignores_bad_rows():
    act = lambda i, at, kind='bjj', deleted=False: dict(entity_type='activities', entity_id=i, deleted=deleted,
                                                         payload=dict(kind=kind, scheduled_at=at))
    records = [act('late', '2026-11-17T04:30:00+00:00'),  # 22:30 on the 16th in Chicago
               act('lift', '2026-11-16T11:00:00-06:00', 'weights'), act('prep', '2026-11-16T16:30:00-06:00', 'prep'),
               act('gone', '2026-11-16T09:00:00-06:00', deleted=True), act('broken', 'not a time')]
    assert [r['entity_id'] for r in training_on(records, date(2026, 11, 16))] == ['lift', 'late']

