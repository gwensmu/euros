"""Acceptance checks for the authored alternating reference week."""
import json
from pathlib import Path
from datetime import date,timedelta
import unittest

class ReferenceWeekTests(unittest.TestCase):
 def test_week_has_four_meals_and_consistent_daily_macros(self):
  commands=json.loads((Path(__file__).resolve().parents[1]/'plan/meal-plan-commands.json').read_text())
  self.assertEqual(7,len(commands))
  for i,c in enumerate(commands):
   day=(date(2026,10,19)+timedelta(days=i)).isoformat();p=c['payload']
   self.assertEqual('meal-plan-'+day,c['entity_id']);self.assertEqual(day,p['date'])
   self.assertEqual('A' if i%2==0 else 'B',p['reference_day'])
   self.assertEqual({'breakfast','lunch','dinner','recovery-snack'},{m['id'] for m in p['meals']})
   self.assertEqual(commands[0]['payload']['nutrition_targets'],p['nutrition_targets'])
   for field,target,tolerance in [('proteinG',130,5),('carbohydrateG',225,5),('fatG',52,2)]:
    self.assertAlmostEqual(target,sum(m[field] for m in p['meals']),delta=tolerance)
   self.assertTrue(all(m['foods'] and m['localTime'] for m in p['meals']))

if __name__=='__main__':unittest.main()
