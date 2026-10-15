import json
from pathlib import Path
from datetime import date
import unittest
from generate_meal_rotation import generate

class RotationTests(unittest.TestCase):
 def setUp(self):self.week=json.loads((Path(__file__).resolve().parents[1]/'plan/meal-plan-commands.json').read_text())
 def test_policy_contract_and_android_asset_match_authored_plan(self):
  root=Path(__file__).resolve().parents[1]
  plan=json.loads((root/'plan/euros.json').read_text())
  contract=json.loads((root/'contracts/nutrition-policy.json').read_text())
  self.assertEqual(contract,dict(goal=plan['goal'],nutrition=plan['nutrition']))
  self.assertEqual((root/'plan/euros.json').read_text(),(root/'android/app/src/main/assets/euros.json').read_text())
 def test_daily_alternation_crosses_week_month_year(self):
  result={c['payload']['date']:c['payload'] for c in generate(self.week)}
  self.assertEqual(87,len(result))
  for day,variant in [('2026-10-19','A'),('2026-10-26','B'),('2027-01-01','A'),('2027-01-13','A')]:self.assertEqual(variant,result[day]['reference_day'])
  self.assertEqual('22:00',result['2026-10-29']['meals'][-1]['localTime'])
 def test_existing_overrides_and_tombstones_are_never_recreated(self):
  ids={c['entity_id'] for c in generate(self.week)}
  self.assertEqual([],generate(self.week,existing_ids=ids))
 def test_source_templates_unchanged_and_meal_ids_stable(self):
  original=json.dumps(self.week)
  for c in generate(self.week):self.assertEqual({'breakfast','lunch','dinner','recovery-snack'},{m['id'] for m in c['payload']['meals']})
  self.assertEqual(original,json.dumps(self.week))
