"""Dated nutrition policy. Planned tiers are snapshots, never tracker calorie eat-back.

The one home for a day's targets: which tier its training implies, when maintenance begins
and what maintenance adds. Callers (meal drafting, totals) ask for the targets, never why.

The constants below are the defaults an account starts with. A user who sets their own
calorie goals and goal weight passes that plan (see plan.py) into resolve, which then uses
their tiers instead. Dates before EFFECTIVE_FROM keep the original dated policy for every
account, so already-recorded history is never recomputed under a plan set later.
"""
from datetime import date, timedelta
from decimal import Decimal

from .localtime import local_day, local_today

GOAL_LB = Decimal('155')
ENABLED_FROM = '2026-10-15'
EFFECTIVE_FROM = '2026-10-22'
DAILY = dict(kcal=1900, proteinG=130, carbohydrateG=225, fatG=dict(min=50, max=53))
MAINTENANCE = dict(kcal=2050, proteinG=130, carbohydrateG=250, fatG=59)
TIERS = {
    'recovery': dict(kcal=1850, proteinG=120, carbohydrateG=165, fatG=72),
    'lifting': dict(kcal=2050, proteinG=120, carbohydrateG=220, fatG=70),
    'bjj': dict(kcal=2300, proteinG=120, carbohydrateG=305, fatG=60),
    'double': dict(kcal=2550, proteinG=120, carbohydrateG=370, fatG=59),
}
# Maintenance removes the estimated deficit as carbohydrate; protein and fat stay the same.
MAINTENANCE_ADD = dict(kcal=200, carbohydrateG=50)
TRAINING_KINDS = ('bjj', 'weights')


def maintenance_targets(targets, addition=None):
    """A tier's targets with the maintenance addition applied."""
    out = dict(targets)
    for key, extra in (addition or MAINTENANCE_ADD).items():
        out[key] = float(Decimal(str(targets[key])) + Decimal(str(extra)))
    return out


def plan_policy(plan=None):
    """The goal weight, tiers and maintenance addition in force: a user's plan, else the defaults."""
    if not plan:
        return GOAL_LB, TIERS, MAINTENANCE_ADD, True
    goals = plan['calorie_goals']
    return (Decimal(str(plan['goal']['weight_lb'])), goals['tiers'], goals['maintenance_add'],
            bool(plan['goal']['maintenance']))


def _scheduled_day(payload):
    try:
        return local_day(payload['scheduled_at'])
    except (KeyError, TypeError, ValueError):
        return None


def training_on(records, day):
    """Live bjj/weights activity records scheduled on a local date, earliest first.

    Skipped sessions still count: a skip prompts review but never lowers the day's budget.
    """
    return sorted((r for r in records if r.get('entity_type') == 'activities' and not r.get('deleted')
                   and r['payload'].get('kind') in TRAINING_KINDS and _scheduled_day(r['payload']) == day),
                  key=lambda r: r['payload']['scheduled_at'])


def tier_for(kinds):
    """The planned training tier for a day's session kinds."""
    kinds = list(kinds)
    if len(kinds) >= 2:
        return 'double'
    if 'bjj' in kinds:
        return 'bjj'
    return 'lifting' if kinds else 'recovery'


def maintenance_start(records, today, goal_lb=GOAL_LB):
    # One daily observation: earliest measured reading, stable ID breaks ties.
    daily = {}
    for r in sorted(records, key=lambda r: (r.get('payload', {}).get('measured_at', ''), r.get('entity_id', ''))):
        p = r.get('payload', {})
        if r.get('deleted') or r.get('entity_type') != 'weight_entries':
            continue
        day, weight = p['local_date'], Decimal(str(p['weight_lb']))
        if ENABLED_FROM <= day <= today and weight > 0:
            daily.setdefault(day, weight)
    for end in sorted(daily):
        dates = [(date.fromisoformat(end) - timedelta(days=i)).isoformat() for i in range(7)]
        if all(d in daily for d in dates) and sum(daily[d] for d in dates) / 7 <= goal_lb:
            return max(end, EFFECTIVE_FROM)
    return None


def moved_tier(records, local_date):
    """The scheduled tier for a date that a nutrition-adjusting training move touched, else None."""
    if local_date < EFFECTIVE_FROM:
        return None
    day = date.fromisoformat(local_date)
    moved = lambda m: m.get('adjust_nutrition') and any(
        _scheduled_day({'scheduled_at': m.get(k)}) == day for k in ('from', 'to'))
    touched = any(moved(m) for r in records if r.get('entity_type') == 'activities' and not r.get('deleted')
                  and r['payload'].get('kind') in TRAINING_KINDS for m in r['payload'].get('move_history', []))
    if not touched:
        return None
    return tier_for(r['payload']['kind'] for r in training_on(records, day))


def resolve(records, local_date, saved_targets=None, today=None, plan=None):
    today = today or local_today().isoformat()
    goal_lb, tiers, addition, maintenance_enabled = plan_policy(plan)
    if local_date < EFFECTIVE_FROM:
        qualifying = [r['payload']['local_date'] for r in records if not r.get('deleted') and r.get('entity_type') == 'weight_entries'
                      and ENABLED_FROM <= r['payload']['local_date'] <= min(today, local_date)
                      and Decimal('0') < Decimal(str(r['payload']['weight_lb'])) <= GOAL_LB]
        start = min(qualifying, default=None)
        return dict(mode='maintenance' if start else 'loss', maintenance_start=start,
                    targets=MAINTENANCE if start else saved_targets or DAILY)
    start = maintenance_start(records, today, goal_lb) if maintenance_enabled else None
    active = start is not None and local_date >= start and start <= today
    tier = moved_tier(records, local_date)
    targets = dict(tiers[tier] if tier else saved_targets or tiers['recovery'])
    if active:
        targets = maintenance_targets(targets, addition)
    return dict(mode='maintenance' if active else 'loss', maintenance_start=start, targets=targets)
