"""Owner-configured initial maintenance targets, never an inferred energy requirement."""
from datetime import date, datetime
from decimal import Decimal
from zoneinfo import ZoneInfo

GOAL_LB = Decimal('155')
ENABLED_FROM = '2026-10-15'
DAILY = dict(kcal=1900, proteinG=130, carbohydrateG=225, fatG=dict(min=50,max=53))
MAINTENANCE = dict(kcal=2050, proteinG=130, carbohydrateG=250, fatG=59)

def resolve(records, local_date, saved_targets=None, today=None):
    today = today or datetime.now(ZoneInfo('America/Chicago')).date().isoformat()
    qualifying = [r['payload']['local_date'] for r in records
                  if not r.get('deleted') and r.get('entity_type') == 'weight_entries'
                  and ENABLED_FROM <= r['payload']['local_date'] <= today
                  and Decimal('0') < Decimal(str(r['payload']['weight_lb'])) <= GOAL_LB]
    start = min(qualifying, default=None)
    active = start is not None and local_date >= start
    return dict(mode='maintenance' if active else 'loss', maintenance_start=start,
                targets=MAINTENANCE if active else saved_targets or DAILY)
