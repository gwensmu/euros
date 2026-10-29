import json
from pathlib import Path
from euros_server.nutrition_policy import resolve, DAILY, MAINTENANCE, GOAL_LB

def weight(day, value, deleted=False):
    return dict(entity_type='weight_entries', deleted=deleted, payload=dict(local_date=day,weight_lb=value))

def test_threshold_and_historical_targets():
    records=[weight('2026-10-18','155'),weight('2026-10-19','157')]
    assert resolve(records,'2026-10-17',today='2026-10-20')['targets']==DAILY
    assert resolve(records,'2026-10-20',today='2026-10-20')['targets']==MAINTENANCE
    assert resolve(records,'2026-10-20',today='2026-10-20')['maintenance_start']=='2026-10-18'

def test_invalid_triggers_and_corrected_weight():
    records=[weight('2026-10-14','150'),weight('2026-10-21','150'),weight('2026-10-18','155',True),weight('2026-10-19','155.01')]
    assert resolve(records,'2026-10-20',today='2026-10-20')['mode']=='loss'

def test_policy_matches_android_authored_config():
    root=Path(__file__).resolve().parents[2]
    plan=json.loads((root/'contracts/nutrition-policy.json').read_text())
    assert plan['nutrition']['daily']==DAILY
    assert plan['nutrition']['maintenance']==MAINTENANCE
    assert plan['goal']['selectedGoalLb']==GOAL_LB

def test_seven_distinct_consecutive_days_and_persistent_transition():
    records=[weight(f'2026-10-{d}', '155') for d in range(22,29)]
    assert resolve(records[:-1],'2026-10-28',today='2026-10-28')['mode']=='loss'
    result=resolve(records+[weight('2026-10-29','160')],'2026-10-29',today='2026-10-29')
    assert result['maintenance_start']=='2026-10-28'
    assert result['targets']['kcal']==2050
    assert resolve(records,'2026-10-27',today='2026-10-29')['mode']=='loss'
    records[2]['deleted']=True
    assert resolve(records,'2026-10-29',today='2026-10-29')['mode']=='loss'

def test_daily_reading_not_seven_readings_same_day_or_future():
    assert resolve([weight('2026-10-22','154') for _ in range(7)],'2026-10-28',today='2026-10-28')['mode']=='loss'
    records=[weight(f'2026-10-{d}', '154') for d in range(22,29)]
    assert resolve(records,'2026-10-28',today='2026-10-27')['mode']=='loss'

def test_maintenance_adds_to_saved_tier_without_mutation():
    from euros_server.nutrition_policy import TIERS
    records=[weight(f'2026-10-{d}','155') for d in range(22,29)]
    for name,t in TIERS.items():
        actual=resolve(records,'2026-11-01',t,today='2026-10-29')['targets']
        assert actual['kcal']==t['kcal']+200
        assert actual['proteinG']==135
        assert actual['carbohydrateG']==t['carbohydrateG']+50
