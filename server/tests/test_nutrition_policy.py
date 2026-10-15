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
