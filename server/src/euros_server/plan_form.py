"""The training-plan form: the fields a user edits, and the plan a submission means.

Rendering and parsing live together because they are two sides of one contract — the field
names (`tier.<day>.<macro>`, `meal.<slot>.time`, `session.<row>.weekday`) have to agree, and
a row the form omits has to parse back to an absent meal or session rather than a blank one.
What a plan may contain is plan.py's decision; this module only reads the browser's version
of it and lets plan.validate refuse anything it got wrong.
"""
from datetime import date, timedelta
from html import escape

from .domain import DomainError
from .pages import field, hidden, notice, select
from .plan import MACRO_KEYS, PLANNED_SLOTS, TIER_NAMES, WEEKDAYS

# Blank rows offered beyond the sessions already planned, so the schedule can grow.
SESSION_ROWS = 8
GENERATE_DAYS = 28
TIER_LABELS = {'recovery': 'Recovery / rest day', 'lifting': 'Lifting only', 'bjj': 'One BJJ session',
               'double': 'Two sessions'}
MACRO_LABELS = {'kcal': 'Calories', 'proteinG': 'Protein g', 'carbohydrateG': 'Carbs g', 'fatG': 'Fat g'}
SLOT_LABELS = {'breakfast': 'Breakfast', 'lunch': 'Lunch', 'dinner': 'Dinner', 'recovery-snack': 'Recovery snack'}


# --- reading plan values ----------------------------------------------------------------

def plain(value):
    """A stored amount as a person would type it: 1850 rather than 1850.0."""
    return int(value) if float(value).is_integer() else value


def macro_text(value):
    """One macro as the form shows it: a number, or a band written `min-max`."""
    if isinstance(value, dict):
        return f'{plain(value["min"])}-{plain(value["max"])}'
    return plain(value)


def _number(raw, field_name):
    try:
        value = float((raw or '').strip())
    except ValueError:
        raise DomainError('invalid_plan_quantity', 422, field=field_name) from None
    return int(value) if value.is_integer() else value


def _macro(raw, field_name):
    text = (raw or '').strip()
    # A leading minus is part of the number; any later dash separates a band.
    if '-' in text[1:]:
        low, high = text.split('-', 1)
        return {'min': _number(low, field_name), 'max': _number(high, field_name)}
    return _number(text, field_name)


def plan_from_form(form):
    """The posted form as a complete training plan; plan.validate has the final say."""
    meals = [{'slot': slot, 'local_time': str(form.get(f'meal.{slot}.time', '')),
              'title': str(form.get(f'meal.{slot}.title', '') or SLOT_LABELS[slot])}
             for slot in PLANNED_SLOTS if form.get(f'meal.{slot}.on')]
    schedule = []
    for index in range(SESSION_ROWS):
        clock = str(form.get(f'session.{index}.time', '')).strip()
        if not clock:
            continue
        schedule.append({'weekday': str(form.get(f'session.{index}.weekday', '')),
                         'kind': str(form.get(f'session.{index}.kind', '')),
                         'local_time': clock,
                         'title': str(form.get(f'session.{index}.title', '') or 'Training')})
    return {
        'competition': {'name': str(form.get('competition.name', '')),
                        'date': str(form.get('competition.date', ''))},
        'goal': {'weight_lb': _number(form.get('goal.weight_lb'), 'goal.weight_lb'),
                 'maintenance': bool(form.get('goal.maintenance'))},
        'calorie_goals': {
            'tiers': {name: {macro: _macro(form.get(f'tier.{name}.{macro}'), f'{name}.{macro}')
                             for macro in MACRO_KEYS} for name in TIER_NAMES},
            'maintenance_add': {'kcal': _number(form.get('maintenance.kcal'), 'maintenance.kcal'),
                                'carbohydrateG': _number(form.get('maintenance.carbohydrateG'),
                                                         'maintenance.carbohydrateG')}},
        'meals': meals, 'workout_schedule': schedule}


# --- rendering the form -----------------------------------------------------------------

def _tier_rows(plan):
    return ''.join(
        '<tr><td>' + escape(TIER_LABELS[name]) + '</td>' +
        ''.join(f'<td><input class="small" name="tier.{name}.{macro}" type="text" '
                f'value="{escape(str(macro_text(plan["calorie_goals"]["tiers"][name][macro])))}"></td>'
                for macro in MACRO_KEYS) + '</tr>' for name in TIER_NAMES)


def _meal_rows(plan):
    by_slot = {m['slot']: m for m in plan['meals']}
    return ''.join(
        f'<tr><td><input type="checkbox" name="meal.{slot}.on"'
        f'{" checked" if slot in by_slot else ""}> {escape(SLOT_LABELS[slot])}</td>'
        f'<td><input class="small" type="time" name="meal.{slot}.time" '
        f'value="{escape(by_slot.get(slot, {}).get("local_time", ""))}"></td>'
        f'<td><input type="text" name="meal.{slot}.title" '
        f'value="{escape(by_slot.get(slot, {}).get("title", SLOT_LABELS[slot]))}"></td></tr>'
        for slot in PLANNED_SLOTS)


def _session_rows(plan):
    rows = list(plan['workout_schedule']) + [{}] * max(0, SESSION_ROWS - len(plan['workout_schedule']))
    return ''.join(
        f'<tr><td>{select(f"session.{i}.weekday", [(d, d.title()) for d in WEEKDAYS], row.get("weekday", "monday"))}</td>'
        f'<td><input class="small" type="time" name="session.{i}.time" value="{escape(row.get("local_time", ""))}"></td>'
        f'<td>{select(f"session.{i}.kind", [("bjj", "BJJ"), ("weights", "Weights")], row.get("kind", "bjj"))}</td>'
        f'<td><input type="text" name="session.{i}.title" value="{escape(row.get("title", ""))}"></td></tr>'
        for i, row in enumerate(rows))


def plan_body(record, csrf, today, message=None, kind='notice'):
    """The plan page: the saved plan in editable fields, and the generate-sessions action."""
    plan = record['plan']
    event = date.fromisoformat(plan['competition']['date'])
    through = min(today + timedelta(days=GENERATE_DAYS), event) if event > today else today + timedelta(days=GENERATE_DAYS)
    return (
        f'{notice(message, kind)}'
        f'<form method="post" action="/account/plan">{hidden("csrf", csrf)}'
        f'{hidden("revision", record["revision"])}'
        f'<h2>Competition</h2>'
        f'{field("Event name", "competition.name", plan["competition"]["name"])}'
        f'{field("Date", "competition.date", plan["competition"]["date"], kind="date")}'
        f'<h2>Goal weight</h2>'
        f'{field("Goal weight (lb)", "goal.weight_lb", plan["goal"]["weight_lb"], extra="class=small")}'
        f'<label><input type="checkbox" name="goal.maintenance"'
        f'{" checked" if plan["goal"]["maintenance"] else ""}> '
        f'Switch to maintenance calories once seven daily weights average at or below the goal</label>'
        f'<h2>Calorie goals by training day</h2>'
        f'<p class="muted">One value, or a range like 50-53. Fat may be a range.</p>'
        f'<table><tr><th>Day</th>'
        + ''.join(f'<th>{escape(MACRO_LABELS[m])}</th>' for m in MACRO_KEYS)
        + f'</tr>{_tier_rows(plan)}</table>'
        f'<h2>Maintenance addition</h2>'
        f'<div class="row">'
        f'{field("Calories", "maintenance.kcal", plan["calorie_goals"]["maintenance_add"]["kcal"], extra="class=small")}'
        f'{field("Carbs g", "maintenance.carbohydrateG", plan["calorie_goals"]["maintenance_add"]["carbohydrateG"], extra="class=small")}'
        f'</div>'
        f'<h2>Meals</h2><table><tr><th>Meal</th><th>Time</th><th>Name</th></tr>{_meal_rows(plan)}</table>'
        f'<h2>Weekly workouts</h2>'
        f'<p class="muted">Leave the time empty to drop a row. These are the sessions generated '
        f'for dates after your event, and the pattern meal planning assumes.</p>'
        f'<table><tr><th>Day</th><th>Time</th><th>Type</th><th>Name</th></tr>{_session_rows(plan)}</table>'
        f'<button>Save training plan</button></form>'
        f'<h2>Generate sessions</h2>'
        f'<p class="muted">Creates dated activities from the weekly schedule. Dates that already '
        f'have a session at that time are left alone; nothing logged is changed.</p>'
        f'<form method="post" action="/account/plan/sessions">{hidden("csrf", csrf)}'
        f'<div class="row">{field("From", "date_from", today, kind="date", extra="class=small")}'
        f'{field("To", "date_to", through, kind="date", extra="class=small")}'
        f'</div><button class="quiet">Generate sessions</button></form>')
