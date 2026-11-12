"""Shared vocabulary for dated meal plans: weeks, saved meals, their items and totals.

Drafting, prep and grocery lists all read the same saved meal shape; the helpers here are
the one place that shape is built from the catalog and read back.
"""
from datetime import timedelta

from .domain import MACROS, DomainError, parse_local_date

# Template properties a saved meal carries, so later catalog edits do not change what an
# already-saved plan means (a past meal does not become use-up because its template did).
MEAL_FLAGS = ('batch', 'use_up')


def parse_week(week_start, dates=None):
    """The Monday-to-Sunday week, or the requested dates within it."""
    start = parse_local_date(week_start, 'week_start_must_be_monday')
    if start.weekday() != 0:
        raise DomainError('week_start_must_be_monday')
    week = [start + timedelta(days=i) for i in range(7)]
    if dates is None:
        return start, week
    if not isinstance(dates, list) or not dates or any(not isinstance(d, str) for d in dates):
        raise DomainError('invalid_dates')
    chosen = sorted({parse_local_date(d, 'dates_outside_week') for d in dates if d in {str(w) for w in week}})
    if len(chosen) != len(set(dates)):
        raise DomainError('dates_outside_week', week_start=week_start)
    return start, chosen


def snapshot_item(food_id, food, grams, **extra):
    """A saved meal item: grams plus the food values it was planned with."""
    return dict(food_id=food_id, grams=grams, food_revision=food['revision'], per100g=food['per100g'], **extra)


def meal_flag(meal, templates, flag):
    """A template flag as saved on the meal; meals saved before flags were snapshotted fall back
    to the current template, which is the only information they have."""
    if flag in meal:
        return meal[flag]
    return bool(templates.get(meal.get('template_id'), {}).get(flag))


def meal_totals(meals):
    """Planned macro totals and how many meals have no value for each; never guesses a gap."""
    known = lambda m, k: isinstance(m.get(k), (float, int)) and not isinstance(m.get(k), bool)
    totals = {k: round(sum(float(m[k]) for m in meals if known(m, k)), 1) for k in MACROS}
    missing = {k: sum(not known(m, k) for m in meals) for k in MACROS}
    return totals, missing


def food_grams(meals, include=lambda food_id: True):
    """Grams per food across meals, with how many meals use it: the input to both grocery and prep."""
    out = {}
    for meal in meals:
        for item in meal.get('items', []):
            if include(item['food_id']):
                entry = out.setdefault(item['food_id'], dict(grams=0, meals=0))
                entry['grams'] += item['grams']
                entry['meals'] += 1
    return out
