import json
import sys
import unittest
from pathlib import Path
from datetime import datetime

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_schedule import generate, ROOT


class ScheduleTests(unittest.TestCase):
    def setUp(self):
        self.plan = json.loads((ROOT/'plan/euros.json').read_text())
        self.commands = generate(self.plan)

    def test_confirmed_slots_and_scope(self):
        self.assertEqual(78, len(self.commands))
        training=[c for c in self.commands if c['payload']['kind'] in ('bjj','weights')]
        self.assertEqual(28,len(training))
        self.assertEqual('2026-10-15T16:00:00-05:00', training[0]['payload']['scheduled_at'])
        self.assertEqual('2026-11-15T11:00:00-06:00', training[-1]['payload']['scheduled_at'])
        expected = {0:{'11:00'},1:{'18:00'},3:{'16:00','20:30'},5:{'10:00'},6:{'11:00'}}
        for command in training:
            actual = datetime.fromisoformat(command['payload']['scheduled_at'])
            self.assertIn(actual.strftime('%H:%M'), expected[actual.weekday()])
            self.assertEqual('pending', command['payload']['status'])
            self.assertEqual(0, command['expected_revision'])
            self.assertIsNone(command['payload']['prescription']['durationMinutes'])

    def test_dst_and_stable_replay(self):
        byid = {c['entity_id']:c for c in self.commands}
        self.assertEqual(78, len(byid))
        self.assertTrue(byid['capacity-2026-10-25-weights-sun']['payload']['scheduled_at'].endswith('-05:00'))
        self.assertTrue(byid['capacity-2026-11-01-weights-sun']['payload']['scheduled_at'].endswith('-06:00'))
        self.assertEqual(self.commands, generate(self.plan))

    def test_assets_match_generation(self):
        for path in ['plan/schedule-commands.json','android/app/src/main/assets/schedule-commands.json']:
            self.assertEqual(self.commands, json.loads((ROOT/path).read_text()))

    def test_wednesday_recovery_uses_existing_parent_and_templates(self):
        byid = {c['entity_id']: c['payload'] for c in self.commands}
        parents = [(key, value) for key, value in byid.items() if value['template_id'] == 'rest']
        self.assertEqual(5, len(parents))
        for parent_id, parent in parents:
            children = [p for p in byid.values() if p.get('parent_id') == parent_id]
            self.assertEqual({'breathing', 'hydration', 'tissue', 'legs'}, {p['template_id'] for p in children})
            self.assertEqual(['08:00', '08:05', '08:10', '08:20'], sorted(p['scheduled_at'][11:16] for p in children))
            self.assertEqual('', parent['prescription']['instructions'])
            self.assertTrue(all(p['status'] == 'pending' and p['scheduled_at'][:10] == parent['scheduled_at'][:10] for p in children))
            hydration = next(p for p in children if p['template_id'] == 'hydration')
            self.assertEqual('hydration', hydration['recovery_step_type'])
            self.assertNotIn('amount', hydration)

    def test_dst_gap_and_fold_rejected(self):
        self.plan['phases'][0]['start']='2026-11-01'
        self.plan['phases'][0]['end']='2026-11-01'
        self.plan['schedule']['weeklyActivities']=[{'id':'ambiguous','templateId':'bjj-flow','day':'SUNDAY','localTime':'01:30','sourceRef':'test'}]
        with self.assertRaisesRegex(ValueError, 'Ambiguous'):
            generate(self.plan)
        self.plan['phases'][0]['start']='2026-03-08'
        self.plan['phases'][0]['end']='2026-03-08'
        self.plan['schedule']['weeklyActivities'][0]['localTime']='02:30'
        with self.assertRaisesRegex(ValueError, 'Nonexistent'):
            generate(self.plan)

    def test_protocol_is_reused_without_duplicated_child_placements(self):
        slots=self.plan['schedule']['weeklyActivities']
        self.assertEqual(2,sum(s.get('protocolId')=='recovery-basic' for s in slots))
        self.assertFalse(any(s.get('parentSlotId') in ('rest-wed','recovery-tue') for s in slots))
        self.assertEqual(1,len(self.plan['recoveryProtocols']))

    def test_configured_phase_not_hardcoded_capacity(self):
        self.plan['schedule']['phaseId']='positional'
        commands=generate(self.plan)
        self.assertTrue(commands)
        self.assertTrue(all(c['entity_id'].startswith('positional-') and c['payload']['phase_id']=='positional' for c in commands))
        self.assertTrue(all('2026-11-16'<=c['payload']['scheduled_at'][:10]<='2026-12-13' for c in commands))

    def test_unknown_or_invalid_phase_rejected(self):
        for value in ['missing',None,[], 'taper']:
            with self.subTest(value=value),self.assertRaises(ValueError):
                self.plan['schedule']['phaseId']=value
                generate(self.plan)

    def test_missing_template_and_protocol_rejected(self):
        import copy
        for field in ['templateId','protocolId']:
            for value in ['missing',[]]:
                with self.subTest(field=field,value=value),self.assertRaises(ValueError):
                    p=copy.deepcopy(self.plan);p['schedule']['weeklyActivities'][0][field]=value;generate(p)

    def test_duplicate_template_slot_and_protocol_step_ids_rejected(self):
        import copy
        for path in ['templates','slots','steps']:
            with self.subTest(path=path),self.assertRaises(ValueError):
                p=copy.deepcopy(self.plan)
                values=p['trainingTemplates'] if path=='templates' else p['schedule']['weeklyActivities'] if path=='slots' else p['recoveryProtocols'][0]['steps']
                values.append(copy.deepcopy(values[0]));generate(p)

    def test_duplicate_composed_child_identity_rejected(self):
        self.plan['schedule']['weeklyActivities'].append({'id':'breathing-tue','templateId':'breathing','day':'TUESDAY','localTime':'20:00','sourceRef':'test'})
        with self.assertRaisesRegex(ValueError,'Duplicate generated'):
            generate(self.plan)

    def test_invalid_parenting_rejected(self):
        import copy
        for target in ['absent','moderate-mon','recovery-tue']:
            with self.subTest(target=target),self.assertRaises(ValueError):
                p=copy.deepcopy(self.plan)
                root=next(s for s in p['schedule']['weeklyActivities'] if s['id']=='recovery-tue')
                root['parentSlotId']=target
                generate(p)

    def test_protocol_offsets_validated(self):
        import copy
        for offset in [-1,1.5,True,None,1441]:
            with self.subTest(offset=offset),self.assertRaises(ValueError):
                p=copy.deepcopy(self.plan);p['recoveryProtocols'][0]['steps'][0]['offsetMinutes']=offset;generate(p)
        self.plan['recoveryProtocols'][0]['offsetMode']='implicit'
        with self.assertRaises(ValueError):generate(self.plan)

    def test_protocol_cannot_hide_nested_protocol_or_override_parent(self):
        for field in ['protocolId','parentSlotId','linkedSessionSlotId','localTime','day']:
            self.plan['recoveryProtocols'][0]['steps'][0][field]='invalid'
            with self.subTest(field=field),self.assertRaises(ValueError):generate(self.plan)
            del self.plan['recoveryProtocols'][0]['steps'][0][field]

    def one_day_protocol(self,day,clock,offset,mode='wall_clock'):
        self.plan['phases'][0]['start']=day;self.plan['phases'][0]['end']=day
        self.plan['schedule']['weeklyActivities']=[{'id':'rest','templateId':'rest','day':datetime.fromisoformat(day).strftime('%A').upper(),'localTime':clock,'sourceRef':'test','protocolId':'recovery-basic','protocolInstanceId':'test'}]
        self.plan['recoveryProtocols'][0]['offsetMode']=mode
        self.plan['recoveryProtocols'][0]['steps']=[{'id':'breathing','templateId':'breathing','offsetMinutes':offset}]
        return generate(self.plan)

    def test_wall_clock_offset_rejects_dst_gap_and_fold(self):
        with self.assertRaisesRegex(ValueError,'Nonexistent'):self.one_day_protocol('2026-03-08','01:45',30)
        with self.assertRaisesRegex(ValueError,'Ambiguous'):self.one_day_protocol('2026-11-01','00:45',30)

    def test_elapsed_offset_handles_dst_without_inventing_wall_time(self):
        commands=self.one_day_protocol('2026-03-08','01:45',30,'elapsed')
        child=next(c for c in commands if 'parent_id' in c['payload'])
        self.assertEqual('2026-03-08T03:15:00-05:00',child['payload']['scheduled_at'])

    def test_cross_midnight_step_retains_parent_occurrence_identity(self):
        commands=self.one_day_protocol('2026-10-14','23:50',20)
        child=next(c for c in commands if 'parent_id' in c['payload'])
        self.assertEqual('capacity-2026-10-14-breathing-test',child['entity_id'])
        self.assertEqual('capacity-2026-10-14-rest',child['payload']['parent_id'])
        self.assertEqual('2026-10-15T00:10:00-05:00',child['payload']['scheduled_at'])

    def test_noncanonical_dates_and_bad_zone_rejected(self):
        self.plan['phases'][0]['start']='20261014'
        with self.assertRaisesRegex(ValueError,'YYYY-MM-DD'):generate(self.plan)
        self.plan['phases'][0]['start']='2026-10-14'
        self.plan['schedule']['timeZone']='Invented/Zone'
        with self.assertRaisesRegex(ValueError,'IANA'):generate(self.plan)

if __name__ == '__main__':
    unittest.main()
