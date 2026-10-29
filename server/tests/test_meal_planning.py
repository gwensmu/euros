from euros_server.meal_planning import context,preview
from euros_server.domain import Store
import importlib.util
from pathlib import Path

def test_generated_horizon_totals_and_no_silent_food(tmp_path):
    root=Path(__file__).resolve().parents[2]
    # Packaged release need not ship offline authoring scripts.
    script=root/'scripts/replan_meals.py'
    if not script.exists():return
    spec=importlib.util.spec_from_file_location('replan',script);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
    commands=m.generate([])
    store=Store(tmp_path/'test.sqlite')
    days=[]
    for c in commands:
        store.command(c)
        if c['entity_type']=='meal_plans':
            p=c['payload'];days.append(p['date']);v=preview(p)
            assert abs(v['planned_totals']['kcal']-p['nutrition_targets']['kcal'])<200
            assert v['planned_totals']['proteinG']>=129
            assert all(x['localTime'] for x in p['meals'])
    assert len(days)==84 and days[-1]=='2027-01-13'
    assert store.records('food_entries')['records']==[]
    assert sum(m['template_id']=='ribs_use_up' for c in commands if c['entity_type']=='meal_plans' for m in c['payload']['meals'])==1

def test_context_protects_inventory_and_provenance():
    c=context()
    assert c['templates']['ribs_use_up']['replenish'] is False
    assert 'estimates' in c['coefficient_basis']
