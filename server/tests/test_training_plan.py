"""The training plan a user sets: competition, calorie goals, meals and weekly workouts."""
import copy
from datetime import date

import pytest

from euros_server.domain import DomainError, Store
from euros_server.meal_week import MealWeeks
from euros_server.nutrition_policy import TIERS, resolve
from euros_server.plan import DEFAULTS, PlanSettings, generate_sessions, validate


@pytest.fixture
def store(tmp_path):
    return Store(tmp_path / 'euros.db')


@pytest.fixture
def plans(store):
    return PlanSettings(store)


def changed(**overrides):
    plan = copy.deepcopy(DEFAULTS)
    plan.update(overrides)
    return plan


def test_an_unset_plan_reads_as_the_shipped_defaults(plans):
    record = plans.read()
    assert record == {'revision': 0, 'plan': DEFAULTS, 'source': 'default', 'updated_at': None}
    assert record['plan']['calorie_goals']['tiers'] == TIERS
    assert record['plan']['competition'] == {'name': 'Euros', 'date': '2027-01-13'}


def test_saving_a_plan_is_revision_checked(plans):
    saved = plans.write(changed(competition={'name': 'Pan Ams', 'date': '2027-03-04'}), 0)
    assert saved['revision'] == 1 and saved['source'] == 'user'
    assert saved['plan']['competition']['name'] == 'Pan Ams'
    with pytest.raises(DomainError) as conflict:
        plans.write(DEFAULTS, 0)
    assert conflict.value.body['error'] == 'revision_conflict'
    assert conflict.value.body['current']['plan']['competition']['name'] == 'Pan Ams'
    assert plans.write(DEFAULTS, 1)['revision'] == 2


def test_a_plan_must_be_complete_and_sensible():
    assert validate(DEFAULTS) == DEFAULTS
    with pytest.raises(DomainError, match='invalid_training_plan'):
        validate({'competition': DEFAULTS['competition']})
    for field, value, error in [
        ('competition', {'name': 'x', 'date': '13-01-2027'}, 'invalid_competition_date'),
        ('competition', {'name': '  ', 'date': '2027-01-13'}, 'invalid_plan_text'),
        ('goal', {'weight_lb': 20, 'maintenance': True}, 'invalid_goal'),
        ('goal', {'weight_lb': 155, 'maintenance': 'yes'}, 'invalid_goal'),
        ('meals', [{'slot': 'brunch', 'title': 'Brunch', 'local_time': '10:00'}], 'invalid_meal_slot'),
        ('meals', [{'slot': 'lunch', 'title': 'A', 'local_time': '25:00'}], 'invalid_plan_time'),
        ('meals', [{'slot': 'lunch', 'title': 'A', 'local_time': '12:00'},
                   {'slot': 'lunch', 'title': 'B', 'local_time': '13:00'}], 'duplicate_meal_slot'),
        ('workout_schedule', [{'weekday': 'funday', 'kind': 'bjj', 'local_time': '18:00', 'title': 'X'}],
         'invalid_weekday'),
        ('workout_schedule', [{'weekday': 'monday', 'kind': 'swimming', 'local_time': '18:00', 'title': 'X'}],
         'invalid_session_kind'),
        ('workout_schedule', [{'weekday': 'monday', 'kind': 'bjj', 'local_time': '18:00', 'title': 'X'},
                              {'weekday': 'monday', 'kind': 'weights', 'local_time': '18:00', 'title': 'Y'}],
         'duplicate_session_time'),
    ]:
        with pytest.raises(DomainError, match=error):
            validate(changed(**{field: value}))


def test_calorie_goals_accept_whole_numbers_and_bands():
    tiers = copy.deepcopy(DEFAULTS['calorie_goals']['tiers'])
    tiers['bjj']['fatG'] = {'min': 50, 'max': 53}
    clean = validate(changed(calorie_goals={'tiers': tiers,
                                            'maintenance_add': {'kcal': 200, 'carbohydrateG': 50}}))
    assert clean['calorie_goals']['tiers']['bjj']['fatG'] == {'min': 50, 'max': 53}
    tiers['bjj']['fatG'] = {'min': 60, 'max': 50}
    with pytest.raises(DomainError, match='invalid_plan_quantity'):
        validate(changed(calorie_goals={'tiers': tiers, 'maintenance_add': {'kcal': 200, 'carbohydrateG': 50}}))


def test_targets_follow_the_users_own_calorie_goals(plans):
    tiers = copy.deepcopy(DEFAULTS['calorie_goals']['tiers'])
    tiers['recovery']['kcal'] = 1600
    plan = plans.write(changed(goal={'weight_lb': 149, 'maintenance': True},
                               calorie_goals={'tiers': tiers,
                                              'maintenance_add': {'kcal': 300, 'carbohydrateG': 60}}), 0)['plan']
    assert resolve([], '2026-12-01', plan=plan)['targets']['kcal'] == 1600
    assert resolve([], '2026-12-01')['targets']['kcal'] == TIERS['recovery']['kcal']
    # Seven daily readings decide maintenance against this user's goal weight, not the default.
    above_their_goal = _week_of_weights('152')
    assert resolve(above_their_goal, '2026-11-20', plan=plan, today='2026-11-20')['mode'] == 'loss'
    assert resolve(above_their_goal, '2026-11-20', today='2026-11-20')['mode'] == 'maintenance'
    maintained = resolve(_week_of_weights('148'), '2026-11-20', plan=plan, today='2026-11-20')
    assert maintained['mode'] == 'maintenance'
    # Their own maintenance addition, on their own recovery tier: 1600 + 300.
    assert maintained['targets']['kcal'] == 1900


def test_maintenance_can_be_switched_off(plans):
    plan = plans.write(changed(goal={'weight_lb': 155, 'maintenance': False}), 0)['plan']
    weights = _week_of_weights('150')
    assert resolve(weights, '2026-11-20', plan=plan, today='2026-11-20')['mode'] == 'loss'
    assert resolve(weights, '2026-11-20', today='2026-11-20')['mode'] == 'maintenance'


def _week_of_weights(pounds):
    return [{'entity_type': 'weight_entries', 'entity_id': f'w{i}', 'deleted': False,
             'payload': {'local_date': f'2026-11-{14 + i:02d}', 'measured_at': f'2026-11-{14 + i:02d}T07:00:00Z',
                         'weight_lb': pounds}} for i in range(7)]


def test_generated_sessions_are_dated_activities_and_never_repeat(store, plans):
    plan = plans.plan()
    first = generate_sessions(store, plan, '2026-12-01', '2026-12-07')
    assert [(s['date'], s['kind'], s['local_time']) for s in first['created']] == [
        ('2026-12-01', 'bjj', '18:30'), ('2026-12-03', 'bjj', '18:30'),
        ('2026-12-05', 'bjj', '10:00'), ('2026-12-06', 'weights', '11:00')]
    saved = store.records('activities')['records']
    assert saved[0]['payload']['scheduled_at'] == '2026-12-01T18:30:00-06:00'
    assert saved[0]['payload']['status'] == 'pending'
    again = generate_sessions(store, plan, '2026-12-01', '2026-12-07')
    assert again['created'] == [] and len(again['skipped']) == 4
    assert len(store.records('activities')['records']) == 4


def test_generating_never_overwrites_a_logged_or_deleted_session(store, plans):
    plan = plans.plan()
    created = generate_sessions(store, plan, '2026-12-01', '2026-12-01')['created'][0]
    done = store.records('activities')['records'][0]
    store.command({'command_id': 'log-1', 'entity_type': 'activities', 'entity_id': created['entity_id'],
                   'expected_revision': 1, 'operation': 'upsert',
                   'payload': {**done['payload'], 'status': 'done'}})
    store.command({'command_id': 'delete-1', 'entity_type': 'activities', 'entity_id': created['entity_id'],
                   'expected_revision': 2, 'operation': 'delete', 'payload': {}})
    # A session the user deleted is not recreated, and nothing logged is touched.
    assert generate_sessions(store, plan, '2026-12-01', '2026-12-01')['created'] == []
    assert store.records('activities')['records'] == []


def test_a_user_scheduled_session_blocks_a_generated_duplicate(store, plans):
    store.command({'command_id': 'own-1', 'entity_type': 'activities', 'entity_id': 'my-class',
                   'expected_revision': 0, 'operation': 'upsert',
                   'payload': {'title': 'Open mat', 'kind': 'bjj', 'scheduled_at': '2026-12-01T18:30:00-06:00',
                               'status': 'pending'}})
    result = generate_sessions(store, plans.plan(), '2026-12-01', '2026-12-01')
    assert result['created'] == [] and result['skipped'][0]['reason'] == 'already scheduled'


def test_generation_refuses_an_unbounded_range(store, plans):
    with pytest.raises(DomainError, match='invalid_plan_date_range'):
        generate_sessions(store, plans.plan(), '2026-12-01', '2028-12-01')
    with pytest.raises(DomainError, match='invalid_plan_date_range'):
        generate_sessions(store, plans.plan(), '2026-12-07', '2026-12-01')


def test_meal_drafting_follows_the_users_meals_and_weekly_pattern(store, plans):
    plans.write(changed(
        competition={'name': 'Pan Ams', 'date': '2026-12-05'},
        meals=[{'slot': 'breakfast', 'title': 'Breakfast', 'local_time': '09:00'},
               {'slot': 'dinner', 'title': 'Dinner', 'local_time': '19:30'}],
        workout_schedule=[{'weekday': 'monday', 'kind': 'bjj', 'local_time': '07:00', 'title': 'Morning class'}]), 0)
    week = MealWeeks(store, plans).draft('2026-12-07', today=date(2026, 12, 6))
    monday = next(day for day in week['days'] if day['date'] == '2026-12-07')
    assert monday['tier'] == 'bjj'
    draft = _draft_payload(store, week['draft_id'], '2026-12-07')
    # Only the slots this user plans appear, and a pre-training snack before their 07:00 class.
    assert {meal['id'] for meal in draft['meals']} <= {'breakfast', 'dinner', 'pre-training'}
    assert next(m for m in draft['meals'] if m['id'] == 'pre-training')['localTime'] == '05:30'
    assert next(m for m in draft['meals'] if m['id'] == 'dinner')['localTime'] == '19:30'
    tuesday = _draft_payload(store, week['draft_id'], '2026-12-08')
    assert tuesday['reference_day'] == 'recovery'


def _draft_payload(store, draft_id, date):
    import json
    with store.connect() as db:
        body = json.loads(db.execute('SELECT body FROM meal_week_drafts WHERE draft_id=?', (draft_id,)).fetchone()[0])
    return next(plan['payload'] for plan in body['plans'] if plan['payload']['date'] == date)
