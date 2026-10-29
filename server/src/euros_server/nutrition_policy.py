"""Dated owner policy. Planned tiers are snapshots, never tracker calorie eat-back."""
from datetime import date, datetime, timedelta
from decimal import Decimal
from zoneinfo import ZoneInfo

GOAL_LB = Decimal('155')
ENABLED_FROM = '2026-10-15'
EFFECTIVE_FROM = '2026-10-22'
DAILY = dict(kcal=1900, proteinG=130, carbohydrateG=225, fatG=dict(min=50,max=53))
MAINTENANCE = dict(kcal=2050, proteinG=130, carbohydrateG=250, fatG=59)
TIERS = {
    'recovery': dict(kcal=1850, proteinG=135, carbohydrateG=165, fatG=72),
    'lifting': dict(kcal=2050, proteinG=135, carbohydrateG=220, fatG=70),
    'bjj': dict(kcal=2300, proteinG=135, carbohydrateG=305, fatG=60),
    'double': dict(kcal=2550, proteinG=135, carbohydrateG=370, fatG=59),
}

def maintenance_start(records, today):
    # One daily observation: earliest measured reading, stable ID breaks ties.
    daily = {}
    for r in sorted(records, key=lambda r:(r.get('payload',{}).get('measured_at',''),r.get('entity_id',''))):
        p=r.get('payload',{})
        if r.get('deleted') or r.get('entity_type')!='weight_entries': continue
        day=p['local_date']; weight=Decimal(str(p['weight_lb']))
        if ENABLED_FROM<=day<=today and weight>0: daily.setdefault(day,weight)
    for end in sorted(daily):
        dates=[(date.fromisoformat(end)-timedelta(days=i)).isoformat() for i in range(7)]
        if all(d in daily for d in dates) and sum(daily[d] for d in dates)/7<=GOAL_LB:
            return max(end,EFFECTIVE_FROM)
    return None

def resolve(records, local_date, saved_targets=None, today=None):
    today = today or datetime.now(ZoneInfo('America/Chicago')).date().isoformat()
    if local_date<EFFECTIVE_FROM:
        qualifying=[r['payload']['local_date'] for r in records if not r.get('deleted') and r.get('entity_type')=='weight_entries'
                    and ENABLED_FROM<=r['payload']['local_date']<=min(today,local_date)
                    and Decimal('0')<Decimal(str(r['payload']['weight_lb']))<=GOAL_LB]
        start=min(qualifying,default=None)
        return dict(mode='maintenance' if start else 'loss',maintenance_start=start,targets=MAINTENANCE if start else saved_targets or DAILY)
    start=maintenance_start(records,today)
    active=start is not None and local_date>=start and start<=today
    targets=dict(saved_targets or TIERS['recovery'])
    if active:
        targets['kcal']=Decimal(str(targets['kcal']))+200
        targets['carbohydrateG']=Decimal(str(targets['carbohydrateG']))+50
        targets={k:float(v) if isinstance(v,Decimal) else v for k,v in targets.items()}
    return dict(mode='maintenance' if active else 'loss',maintenance_start=start,targets=targets)
