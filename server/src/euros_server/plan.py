"""The training plan a user sets for themselves: competition, calorie goals, meals, workouts.

These are settings, not records: they say what the plan *is*, while activities, food entries
and weights stay the canonical log of what happened. Nutrition targets and meal drafting read
the settings here instead of module constants, so two accounts can hold different plans. The
defaults reproduce the values this app shipped with, so an account that never edits its plan
behaves exactly as before.

Changing settings never rewrites a saved record. Generating sessions from the weekly workout
schedule creates dated activities explicitly and skips any date that already has one.
"""
from sqlalchemy import select
from .models import TrainingPlan, RecordRow
import json
from datetime import date, datetime, time, timedelta, timezone

from .domain import DomainError, canonical, number, parse_local_date
from .localtime import ZONE
from .nutrition_policy import GOAL_LB, MAINTENANCE_ADD, TIERS, TRAINING_KINDS

WEEKDAYS = ('monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday')
# Meal slots a user schedules directly. pre-training is placed around BJJ, never set by hand.
PLANNED_SLOTS = ('breakfast', 'lunch', 'dinner', 'recovery-snack')
TIER_NAMES = ('recovery', 'lifting', 'bjj', 'double')
MACRO_KEYS = ('kcal', 'proteinG', 'carbohydrateG', 'fatG')
MAX_SESSIONS = 21
MAX_GENERATED_DAYS = 180

DEFAULTS = {
    'competition': {'name': 'Euros', 'date': '2027-01-13'},
    'goal': {'weight_lb': float(GOAL_LB), 'maintenance': True},
    'calorie_goals': {'tiers': {name: dict(values) for name, values in TIERS.items()},
                      'maintenance_add': dict(MAINTENANCE_ADD)},
    'meals': [{'slot': 'breakfast', 'title': 'Breakfast', 'local_time': '08:00'},
              {'slot': 'lunch', 'title': 'Lunch', 'local_time': '12:30'},
              {'slot': 'dinner', 'title': 'Dinner', 'local_time': '18:30'},
              {'slot': 'recovery-snack', 'title': 'Recovery snack', 'local_time': '20:30'}],
    'workout_schedule': [{'weekday': 'tuesday', 'kind': 'bjj', 'local_time': '18:30', 'title': 'BJJ class'},
                         {'weekday': 'thursday', 'kind': 'bjj', 'local_time': '18:30', 'title': 'BJJ class'},
                         {'weekday': 'saturday', 'kind': 'bjj', 'local_time': '10:00', 'title': 'BJJ class'},
                         {'weekday': 'sunday', 'kind': 'weights', 'local_time': '11:00', 'title': 'Weights'}],
}


def _clock(value, field):
    try:
        if not isinstance(value, str) or time.fromisoformat(value).strftime('%H:%M') != value:
            raise ValueError()
    except ValueError:
        raise DomainError('invalid_plan_time', 422, field=field) from None
    return value


def _title(value, field, maximum=80):
    if not isinstance(value, str) or not 1 <= len(value.strip()) <= maximum:
        raise DomainError('invalid_plan_text', 422, field=field)
    return value.strip()


def _macro(value, field):
    """A planned macro amount: a number, or an authored {min, max} band."""
    if isinstance(value, dict):
        if set(value) != {'min', 'max'}:
            raise DomainError('invalid_plan_quantity', 422, field=field)
        low, high = number(value['min'], field), number(value['max'], field)
        if low > high or high > 20000:
            raise DomainError('invalid_plan_quantity', 422, field=field)
        return {'min': _plain(low), 'max': _plain(high)}
    if number(value, field) > 20000:
        raise DomainError('invalid_plan_quantity', 422, field=field)
    return _plain(number(value, field))


def _plain(value):
    """Whole amounts stay integers so a stored plan reads like the authored one."""
    return int(value) if value == value.to_integral_value() else float(value)


def _competition(value):
    if not isinstance(value, dict) or set(value) != {'name', 'date'}:
        raise DomainError('invalid_competition', 422, fields=['name', 'date'])
    day = parse_local_date(value.get('date'), 'invalid_competition_date')
    if not date(2000, 1, 1) <= day <= date(2100, 1, 1):
        raise DomainError('invalid_competition_date', 422)
    return {'name': _title(value.get('name'), 'competition.name', 120), 'date': str(day)}


def _goal(value):
    if not isinstance(value, dict) or set(value) != {'weight_lb', 'maintenance'}:
        raise DomainError('invalid_goal', 422, fields=['weight_lb', 'maintenance'])
    weight = number(value.get('weight_lb'), 'weight_lb', positive=True)
    if not 50 <= weight <= 600:
        raise DomainError('invalid_goal', 422, field='weight_lb')
    if not isinstance(value.get('maintenance'), bool):
        raise DomainError('invalid_goal', 422, field='maintenance')
    return {'weight_lb': _plain(weight), 'maintenance': value['maintenance']}


def _calorie_goals(value):
    if not isinstance(value, dict) or set(value) != {'tiers', 'maintenance_add'}:
        raise DomainError('invalid_calorie_goals', 422, fields=['tiers', 'maintenance_add'])
    tiers = value['tiers']
    if not isinstance(tiers, dict) or set(tiers) != set(TIER_NAMES):
        raise DomainError('invalid_calorie_goals', 422, tiers=list(TIER_NAMES))
    clean = {}
    for name, targets in tiers.items():
        if not isinstance(targets, dict) or set(targets) != set(MACRO_KEYS):
            raise DomainError('invalid_calorie_goals', 422, tier=name, fields=list(MACRO_KEYS))
        clean[name] = {key: _macro(targets[key], f'{name}.{key}') for key in MACRO_KEYS}
    extra = value['maintenance_add']
    if not isinstance(extra, dict) or set(extra) != {'kcal', 'carbohydrateG'}:
        raise DomainError('invalid_maintenance_add', 422, fields=['kcal', 'carbohydrateG'])
    return {'tiers': clean,
            'maintenance_add': {key: _plain(number(extra[key], key)) for key in ('kcal', 'carbohydrateG')}}


def _meals(value):
    if not isinstance(value, list) or not 1 <= len(value) <= len(PLANNED_SLOTS):
        raise DomainError('invalid_plan_meals', 422, slots=list(PLANNED_SLOTS))
    out = []
    for meal in value:
        if not isinstance(meal, dict) or set(meal) != {'slot', 'title', 'local_time'}:
            raise DomainError('invalid_plan_meals', 422, fields=['slot', 'title', 'local_time'])
        if meal['slot'] not in PLANNED_SLOTS:
            raise DomainError('invalid_meal_slot', 422, slots=list(PLANNED_SLOTS))
        out.append({'slot': meal['slot'], 'title': _title(meal['title'], 'meal.title'),
                    'local_time': _clock(meal['local_time'], 'meal.local_time')})
    if len({m['slot'] for m in out}) != len(out):
        raise DomainError('duplicate_meal_slot', 422)
    return sorted(out, key=lambda m: (m['local_time'], m['slot']))


def _workout_schedule(value):
    if not isinstance(value, list) or len(value) > MAX_SESSIONS:
        raise DomainError('invalid_workout_schedule', 422, maximum=MAX_SESSIONS)
    out = []
    for session in value:
        if not isinstance(session, dict) or set(session) != {'weekday', 'kind', 'local_time', 'title'}:
            raise DomainError('invalid_workout_schedule', 422, fields=['weekday', 'kind', 'local_time', 'title'])
        if session['weekday'] not in WEEKDAYS:
            raise DomainError('invalid_weekday', 422, weekdays=list(WEEKDAYS))
        if session['kind'] not in TRAINING_KINDS:
            raise DomainError('invalid_session_kind', 422, kinds=list(TRAINING_KINDS))
        out.append({'weekday': session['weekday'], 'kind': session['kind'],
                    'local_time': _clock(session['local_time'], 'session.local_time'),
                    'title': _title(session['title'], 'session.title')})
    if len({(s['weekday'], s['local_time']) for s in out}) != len(out):
        raise DomainError('duplicate_session_time', 422)
    return sorted(out, key=lambda s: (WEEKDAYS.index(s['weekday']), s['local_time']))


def validate(payload):
    """A complete training plan; partial updates are not accepted, so nothing is silently kept."""
    if not isinstance(payload, dict) or set(payload) != set(DEFAULTS):
        raise DomainError('invalid_training_plan', 422, fields=sorted(DEFAULTS))
    canonical(payload)
    return {'competition': _competition(payload['competition']), 'goal': _goal(payload['goal']),
            'calorie_goals': _calorie_goals(payload['calorie_goals']), 'meals': _meals(payload['meals']),
            'workout_schedule': _workout_schedule(payload['workout_schedule'])}


class PlanSettings:
    """One revision-checked training plan per account, stored beside that account's records."""

    def __init__(self, store):
        self.store = store

    def read(self):
        with self.store.session() as db:
            row = db.get(TrainingPlan, (db.user_id, 1))
        return self._shape(row)

    @staticmethod
    def _shape(row):
        if row is None:
            return {'revision': 0, 'plan': json.loads(canonical(DEFAULTS)), 'source': 'default',
                    'updated_at': None}
        return {'revision': row.revision, 'plan': json.loads(row.payload), 'source': 'user',
                'updated_at': row.updated_at}

    def plan(self):
        return self.read()['plan']

    def write(self, payload, expected_revision):
        if type(expected_revision) is not int or expected_revision < 0:
            raise DomainError('invalid_revision', 422)
        clean = validate(payload)
        updated = datetime.now(timezone.utc).isoformat()
        with self.store.session(write=True) as db:
            row = db.get(TrainingPlan, (db.user_id, 1))
            current = row.revision if row else 0
            if expected_revision != current:
                # From the row already loaded: opening a second session here would hold two
                # pooled connections in one request.
                raise DomainError('revision_conflict', 409, current=self._shape(row))
            db.merge(TrainingPlan(user_id=db.user_id, id=1, revision=current+1, payload=canonical(clean), updated_at=updated))
        return self.read()


def targets_for(plan, tier):
    """The dated targets a tier means under this plan."""
    return dict(plan['calorie_goals']['tiers'][tier])


def meal_clocks(plan):
    """Planned meal times by slot, as meal drafting expects them."""
    return {meal['slot']: meal['local_time'] for meal in plan['meals']}


def weekly_sessions(plan):
    """The weekly workout pattern as {weekday index: [(kind, HH:MM), ...]}."""
    out = {}
    for session in plan['workout_schedule']:
        out.setdefault(WEEKDAYS.index(session['weekday']), []).append((session['kind'], session['local_time']))
    return out


def session_identity(day, kind, clock):
    """A stable activity id for a generated session, so regenerating never duplicates one."""
    return f'plan-{day}-{kind}-{clock.replace(":", "")}'


def generate_sessions(store, plan, date_from, date_to, existing=None):
    """Create dated activities from the weekly workout schedule, skipping dates already scheduled.

    Idempotent: a session this plan already generated, a user-created session at the same time,
    or any deleted generated session is left alone. Nothing logged is ever overwritten.
    """
    start, end = parse_local_date(date_from, 'invalid_plan_date_range'), parse_local_date(date_to, 'invalid_plan_date_range')
    if not 0 <= (end - start).days <= MAX_GENERATED_DAYS:
        raise DomainError('invalid_plan_date_range', 422, maximum_days=MAX_GENERATED_DAYS)
    pattern = weekly_sessions(plan)
    records = store.records()['records'] if existing is None else existing
    live = {r['entity_id'] for r in records if r['entity_type'] == 'activities'}
    with store.session() as db:
        tombstoned = set(db.scalars(select(RecordRow.entity_id).where(RecordRow.entity_type == "activities", RecordRow.deleted == 1)))
    scheduled = {(_local_day(r), _local_clock(r)) for r in records
                 if r['entity_type'] == 'activities' and r['payload'].get('kind') in TRAINING_KINDS}
    created, skipped, commands = [], [], []
    day = start
    while day <= end:
        for kind, clock in pattern.get(day.weekday(), []):
            identity = session_identity(day, kind, clock)
            if identity in live or identity in tombstoned or (str(day), clock) in scheduled:
                skipped.append({'date': str(day), 'kind': kind, 'local_time': clock, 'reason': 'already scheduled'})
                continue
            title = next(s['title'] for s in plan['workout_schedule']
                         if s['kind'] == kind and s['local_time'] == clock and WEEKDAYS.index(s['weekday']) == day.weekday())
            when = datetime.combine(day, time.fromisoformat(clock), ZONE)
            commands.append(dict(command_id=f'plan-session-{identity}', entity_type='activities',
                                 entity_id=identity, expected_revision=0, operation='upsert',
                                 payload={'title': title, 'kind': kind, 'scheduled_at': when.isoformat(),
                                          'status': 'pending', 'source': 'training_plan'}))
            created.append({'date': str(day), 'kind': kind, 'local_time': clock, 'entity_id': identity})
        day += timedelta(days=1)
    if commands:
        with store.session(write=True) as db:
            for command in commands:
                store.command(command, _db=db)
    return {'date_from': str(start), 'date_to': str(end), 'created': created, 'skipped': skipped}


def _local_day(record):
    try:
        return datetime.fromisoformat(record['payload']['scheduled_at']).astimezone(ZONE).date().isoformat()
    except (KeyError, TypeError, ValueError):
        return None


def _local_clock(record):
    try:
        return datetime.fromisoformat(record['payload']['scheduled_at']).astimezone(ZONE).strftime('%H:%M')
    except (KeyError, TypeError, ValueError):
        return None
