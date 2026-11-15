"""The training-plan form: what a submission means, and what the page shows back."""
import copy

import pytest

from euros_server.domain import DomainError
from euros_server.plan import DEFAULTS, validate
from euros_server.plan_form import macro_text, plan_body, plan_from_form
from datetime import date


def posted(**overrides):
    """The default plan as the browser posts it back, before any edit."""
    form = {'competition.name': 'Euros', 'competition.date': '2027-01-13',
            'goal.weight_lb': '155', 'goal.maintenance': 'on',
            'maintenance.kcal': '200', 'maintenance.carbohydrateG': '50'}
    for name, values in {'recovery': (1850, 120, 165, 72), 'lifting': (2050, 120, 220, 70),
                         'bjj': (2300, 120, 305, 60), 'double': (2550, 120, 370, 59)}.items():
        for macro, value in zip(('kcal', 'proteinG', 'carbohydrateG', 'fatG'), values):
            form[f'tier.{name}.{macro}'] = str(value)
    for slot, clock, title in (('breakfast', '08:00', 'Breakfast'), ('lunch', '12:30', 'Lunch'),
                               ('dinner', '18:30', 'Dinner'), ('recovery-snack', '20:30', 'Recovery snack')):
        form.update({f'meal.{slot}.on': 'on', f'meal.{slot}.time': clock, f'meal.{slot}.title': title})
    for index, (weekday, clock, kind, title) in enumerate([('tuesday', '18:30', 'bjj', 'BJJ class'),
                                                           ('thursday', '18:30', 'bjj', 'BJJ class'),
                                                           ('saturday', '10:00', 'bjj', 'BJJ class'),
                                                           ('sunday', '11:00', 'weights', 'Weights')]):
        form.update({f'session.{index}.weekday': weekday, f'session.{index}.time': clock,
                     f'session.{index}.kind': kind, f'session.{index}.title': title})
    form.update(overrides)
    return form


def test_the_unedited_form_posts_back_the_same_plan():
    assert validate(plan_from_form(posted())) == DEFAULTS


def test_an_unchecked_meal_and_an_empty_session_row_disappear():
    form = posted()
    del form['meal.lunch.on']
    form['session.1.time'] = '   '
    plan = plan_from_form(form)
    assert [m['slot'] for m in plan['meals']] == ['breakfast', 'dinner', 'recovery-snack']
    assert [s['weekday'] for s in plan['workout_schedule']] == ['tuesday', 'saturday', 'sunday']


def test_a_macro_may_be_a_number_or_a_band():
    plan = plan_from_form(posted(**{'tier.bjj.fatG': '50-53', 'tier.double.fatG': '59'}))
    assert plan['calorie_goals']['tiers']['bjj']['fatG'] == {'min': 50, 'max': 53}
    assert plan['calorie_goals']['tiers']['double']['fatG'] == 59
    assert macro_text({'min': 50.0, 'max': 53}) == '50-53' and macro_text(1850.0) == 1850


def test_an_amount_that_is_not_a_number_names_its_field():
    with pytest.raises(DomainError) as refused:
        plan_from_form(posted(**{'tier.recovery.kcal': 'lots'}))
    assert refused.value.body == {'error': 'invalid_plan_quantity', 'field': 'recovery.kcal'}
    with pytest.raises(DomainError, match='invalid_plan_quantity'):
        plan_from_form(posted(**{'goal.weight_lb': ''}))
    # A leading minus belongs to the number rather than splitting a band; the plan then refuses it.
    assert plan_from_form(posted(**{'goal.weight_lb': '-155'}))['goal']['weight_lb'] == -155
    with pytest.raises(DomainError, match='invalid_quantity'):
        validate(plan_from_form(posted(**{'goal.weight_lb': '-155'})))


def test_the_page_shows_the_saved_plan_in_fields_it_can_read_back():
    record = {'revision': 3, 'plan': copy.deepcopy(DEFAULTS), 'source': 'user', 'updated_at': None}
    body = plan_body(record, 'csrf-token', date(2026, 12, 1))
    assert 'name="revision" value="3"' in body and 'value="csrf-token"' in body
    assert 'name="tier.recovery.kcal" type="text" value="1850"' in body
    assert 'name="meal.breakfast.on" checked' in body and 'value="08:00"' in body
    # Eight schedule rows: four planned sessions and four empty ones to grow into.
    assert body.count('name="session.') == 8 * 4
    assert 'value="2026-12-01"' in body and 'value="2026-12-29"' in body


def test_the_generate_range_never_runs_past_the_event():
    record = {'revision': 1, 'plan': {**copy.deepcopy(DEFAULTS),
                                      'competition': {'name': 'Euros', 'date': '2026-12-10'}},
              'source': 'user', 'updated_at': None}
    body = plan_body(record, 'csrf', date(2026, 12, 1))
    assert 'value="2026-12-10"' in body and 'value="2026-12-29"' not in body
