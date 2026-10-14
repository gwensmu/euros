#!/usr/bin/env python3
"""Compile authored recurring slots and reusable protocols into stable activity commands."""
import argparse
from datetime import date, datetime, time, timedelta, timezone
import json
from pathlib import Path
import re
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

ROOT = Path(__file__).resolve().parents[1]
DAYS = {name: i for i, name in enumerate(('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY'))}


def fail(condition, message):
    if not condition:
        raise ValueError(message)


def index(items, label):
    fail(isinstance(items, list), f'{label}: expected list')
    result = {}
    for item in items:
        fail(isinstance(item, dict), f'{label}: expected object')
        identity = item.get('id')
        fail(isinstance(identity, str) and bool(re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_-]*', identity)), f'{label}: invalid ID')
        fail(identity not in result, f'{label}: duplicate ID {identity}')
        result[identity] = item
    return result


def local_clock(value):
    fail(isinstance(value, str) and bool(re.fullmatch(r'\d{2}:\d{2}', value)), 'Expected HH:MM local time')
    return time.fromisoformat(value)


def zoned(wall, zone):
    value = wall.replace(tzinfo=zone)
    fail(value.astimezone(timezone.utc).astimezone(zone).replace(tzinfo=None) == wall,
         'Nonexistent local time requires an authored correction')
    fail(value.utcoffset() == wall.replace(tzinfo=zone, fold=1).utcoffset(),
         'Ambiguous local time requires an authored offset')
    return value


def compile_slots(plan):
    """Resolve protocol composition without altering template snapshots or existing IDs."""
    schedule = plan['schedule']
    templates = index(plan['trainingTemplates'], 'templates')
    for template in templates.values():
        fail(template.get('kind') in ('bjj','weights','food','recovery','morning','weight','rest'), 'Invalid template kind')
        fail(isinstance(template.get('title'),str) and bool(template['title'].strip()), 'Template title required')
    protocols = index(plan.get('recoveryProtocols', []), 'protocols')
    for protocol in protocols.values():
        fail(protocol.get('offsetMode') in ('wall_clock', 'elapsed'), 'Protocol requires explicit offsetMode')
        steps = index(protocol.get('steps'), 'protocol steps')
        fail(bool(steps), 'Protocol must contain steps')
        for step in steps.values():
            fail(isinstance(step.get('templateId'),str) and step['templateId'] in templates, 'Missing protocol step template')
            fail(type(step.get('offsetMinutes')) is int and 0 <= step['offsetMinutes'] <= 1440,
                 'Protocol offsets must be integer minutes from 0 through 1440')
            if 'sourceRef' in step:
                fail(isinstance(step['sourceRef'],str) and bool(step['sourceRef']), 'Protocol step sourceRef cannot be empty')
            fail(not any(k in step for k in ('protocolId', 'parentSlotId', 'linkedSessionSlotId', 'localTime', 'day')),
                 'Protocol steps cannot override their parent or nest protocols')
    roots = index(schedule['weeklyActivities'], 'weekly slots')
    slots = dict(roots)
    for slot in roots.values():
        fail(isinstance(slot.get('day'),str) and slot['day'] in DAYS, 'Unknown weekday')
        fail(isinstance(slot.get('templateId'),str) and slot['templateId'] in templates, 'Missing slot template')
        local_clock(slot.get('localTime'))
        fail(isinstance(slot.get('sourceRef'), str) and bool(slot['sourceRef']), 'Slot sourceRef required')
        if slot.get('protocolId') is not None:
            fail(isinstance(slot['protocolId'],str) and slot['protocolId'] in protocols, 'Missing recovery protocol')
            suffix = slot.get('protocolInstanceId')
            fail(isinstance(suffix, str) and bool(re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_-]*', suffix)), 'Protocol instance ID required')
            protocol = protocols[slot['protocolId']]
            for step in protocol['steps']:
                identity = f"{step['id']}-{suffix}"
                fail(identity not in slots, f'Duplicate generated slot identity {identity}')
                child = {'id':identity, 'templateId':step['templateId'], 'day':slot['day'],
                         'parentSlotId':slot['id'], 'sourceRef':step.get('sourceRef',slot['sourceRef']),
                         '_offsetMinutes':step['offsetMinutes'], '_offsetMode':protocol['offsetMode']}
                if step.get('recoveryStepType'):
                    child['recoveryStepType'] = step['recoveryStepType']
                slots[identity] = child
    for slot in slots.values():
        fail(slot.get('recoveryStepType') in (None, 'hydration', 'supplement'), 'Invalid recovery step type')
        for field in ('parentSlotId', 'linkedSessionSlotId'):
            if slot.get(field) is not None:
                fail(isinstance(slot[field],str), f'Invalid {field} reference')
                parent = slots.get(slot[field])
                fail(parent is not None, f'Missing {field} reference')
                fail(parent['day'] == slot['day'], 'Parent references must share the authored recurrence day')
                if field == 'parentSlotId':
                    fail(templates[parent['templateId']]['kind'] in ('rest','recovery'), 'Parent must be rest or recovery')
                else:
                    fail(templates[parent['templateId']]['kind'] == 'bjj', 'Linked session must be BJJ')
    visited, active = set(), set()
    def visit(identity):
        fail(identity not in active, 'Cyclic activity parenting')
        if identity in visited:
            return
        active.add(identity)
        for key in ('parentSlotId','linkedSessionSlotId'):
            if slots[identity].get(key):
                visit(slots[identity][key])
        active.remove(identity)
        visited.add(identity)
    for identity in slots:
        visit(identity)
    return templates, slots


def generate(plan):
    schedule = plan['schedule']
    phases = index(plan['phases'], 'phases')
    phase_id = schedule.get('phaseId')
    fail(isinstance(phase_id,str) and phase_id in phases, 'Missing configured phase')
    phase = phases[phase_id]
    try:
        start, end = date.fromisoformat(phase['start']), date.fromisoformat(phase['end'])
    except (ValueError, TypeError, KeyError) as exc:
        raise ValueError('Configured phase requires valid start and end dates') from exc
    fail(start.isoformat()==phase['start'] and end.isoformat()==phase['end'], 'Phase dates must use YYYY-MM-DD')
    fail(start <= end, 'Phase start must not follow end')
    fail((end-start).days <= 366, 'Authored phase cannot exceed 367 days')
    try:
        zone = ZoneInfo(schedule['timeZone'])
    except (ZoneInfoNotFoundError, TypeError, ValueError) as exc:
        raise ValueError('Valid IANA schedule timeZone required') from exc
    templates, slots = compile_slots(plan)
    commands, identities = [], set()
    current = start
    while current <= end:
        times = {}
        def scheduled_time(slot):
            if slot['id'] in times:
                return times[slot['id']]
            if '_offsetMinutes' in slot:
                anchor = scheduled_time(slots[slot['parentSlotId']])
                delta = timedelta(minutes=slot['_offsetMinutes'])
                result = ((anchor.astimezone(timezone.utc)+delta).astimezone(zone) if slot['_offsetMode']=='elapsed'
                          else zoned(anchor.replace(tzinfo=None)+delta,zone))
            else:
                result = zoned(datetime.combine(current, local_clock(slot['localTime'])),zone)
            times[slot['id']] = result
            return result
        for slot in slots.values():
            if current.weekday() != DAYS[slot['day']]:
                continue
            template = templates[slot['templateId']]
            scheduled = scheduled_time(slot)
            identity = f"{phase_id}-{current.isoformat()}-{slot['id']}"
            fail(identity not in identities, 'Duplicate scheduled identity')
            identities.add(identity)
            payload = {'title':template['title'], 'kind':template['kind'],
                       'scheduled_at':scheduled.isoformat(), 'status':'pending',
                       'plan_revision':plan['revision'], 'template_id':template['id'],
                       'phase_id':phase_id, 'time_zone':schedule['timeZone'],
                       'schedule_source':slot['sourceRef'], 'prescription':template,
                       'plan_snapshot': {'template': template, 'category':template.get('category'),
                                         'nutrition_profile': ('trainingAB' if template.get('category') in ('competition','moderate') else 'flowRest' if template.get('category') == 'flow' else None)}}
            for author_field,record_field in (('parentSlotId','parent_id'),('linkedSessionSlotId','linked_session_id')):
                if slot.get(author_field):
                    payload[record_field] = f"{phase_id}-{current.isoformat()}-{slot[author_field]}"
            if slot.get('recoveryStepType'):
                payload['recovery_step_type'] = slot['recoveryStepType']
            if 'category' in template:
                payload['category'] = template['category']
            commands.append({'command_id':f"create-{identity}", 'entity_type':'activities',
                             'entity_id':identity, 'expected_revision':0, 'operation':'upsert', 'payload':payload})
        current += timedelta(days=1)
    return sorted(commands, key=lambda c:(c['payload']['scheduled_at'],c['entity_id']))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    plan = json.loads((ROOT/'plan/euros.json').read_text())
    content = json.dumps(generate(plan), indent=2) + '\n'
    targets = [ROOT/'plan/schedule-commands.json', ROOT/'android/app/src/main/assets/schedule-commands.json']
    if args.check:
        if any(not p.exists() or p.read_text() != content for p in targets):
            raise SystemExit('Generated schedule is stale; run scripts/generate_schedule.py')
    else:
        for path in targets:
            path.write_text(content)
    print(f'{len(json.loads(content))} scheduled activities; no completion records written')


if __name__ == '__main__':
    main()
