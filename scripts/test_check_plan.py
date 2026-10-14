"""Run with python -m unittest discover -s scripts -p 'test_check_plan.py'."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('check_plan', ROOT / 'scripts/check-plan.py')
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


class PlanValidationTests(unittest.TestCase):
    def setUp(self):
        self.plan = json.loads((ROOT / 'plan/euros.json').read_text())

    def errors(self):
        return '\n'.join(validator.validate_plan(self.plan)[0])

    def test_seed_allowed_without_mutating_nulls_or_creating_schedule(self):
        original = copy.deepcopy(self.plan)
        errors, gaps = validator.validate_plan(self.plan)
        self.assertEqual([], errors)
        self.assertTrue(any('scheduledActivities' in gap for gap in gaps))
        self.assertTrue(any('taper' in gap for gap in gaps))
        self.assertEqual(original, self.plan)
        self.assertIsNone(self.plan['nutrition']['trainingAB']['proteinG'])
        self.assertNotIn('scheduledActivities', self.plan)

    def test_impossible_and_noncanonical_event_dates(self):
        for value in ('2027-02-29', '20270113', '2027-1-13', None, 2027):
            with self.subTest(value=value):
                self.plan['event']['date'] = value
                self.assertIn('event.date', self.errors())

    def test_duplicate_phase(self):
        self.plan['phases'][1]['id'] = 'capacity'
        self.assertIn('duplicate phase', self.errors())

    def test_inclusive_overlap(self):
        self.plan['phases'][1]['start'] = self.plan['phases'][0]['end']
        self.assertIn('overlaps', self.errors())

    def test_nested_overlap_detected(self):
        self.plan['phases'][0]['end'] = '2027-01-05'
        errors = self.errors()
        self.assertIn('phases[1]: overlaps phases[0]', errors)
        self.assertIn('phases[3]: overlaps phases[0]', errors)

    def test_invalid_phase_order_and_event_boundary(self):
        self.plan['phases'][0]['start'] = '2027-01-13'
        errors = self.errors()
        self.assertIn('start must not follow end', errors)
        self.assertIn('precede event', errors)

    def test_bad_quantities(self):
        for value in (-1, float('nan'), float('inf'), -float('inf')):
            with self.subTest(value=value):
                self.plan['nutrition']['trainingAB']['kcal'] = value
                self.assertIn('finite and nonnegative', self.errors())

    def test_ranges_and_unknown_or_zero(self):
        self.plan['goal']['lossLb'] = {'min': 8, 'max': 5}
        self.assertIn('min must not exceed max', self.errors())
        self.plan['goal']['lossLb'] = {'min': 0, 'max': 5}
        self.assertEqual('', self.errors())

    def test_scheduled_activities_require_offset_and_unique_ids(self):
        self.plan['scheduledActivities'] = [
            {'id': 'a', 'scheduled_at': '2026-10-14T18:00:00-05:00'}]
        self.assertEqual('', self.errors())
        self.plan['scheduledActivities'][0]['scheduled_at'] = '2026-10-14T18:00'
        self.assertIn('UTC offset required', self.errors())
        self.plan['scheduledActivities'].append({'id': 'a'})
        self.assertIn('duplicate activity ID', self.errors())
        self.assertIn('required ISO date/time', self.errors())

    def test_invalid_shapes_do_not_crash(self):
        self.assertTrue(validator.validate_plan([])[0])
        self.plan['phases'] = [None, {'id': []}]
        self.assertIn('expected object', self.errors())
        self.assertIn('nonempty ID', self.errors())

    def test_contract_activity_fixtures_have_valid_timestamps(self):
        for path in (ROOT / 'contracts').glob('*.json'):
            command = json.loads(path.read_text())
            if command.get('entity_type') == 'activities':
                with self.subTest(path=path.name):
                    self.assertIsNone(validator.validate_timestamp(command['payload'].get('scheduled_at')))


if __name__ == '__main__':
    unittest.main()
