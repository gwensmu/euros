"""Generate missing dated A/B defaults; existing IDs (including tombstones) win."""
import argparse
import copy
from datetime import date, timedelta
import json
from pathlib import Path

ANCHOR = date(2026, 10, 19)
END = date(2027, 1, 13)

def generate(reference_week, start=ANCHOR, end=END, existing_ids=()):
    if end < start or start < ANCHOR:
        raise ValueError('Invalid rotation range')
    templates = {c['payload']['reference_day']: c['payload'] for c in reversed(reference_week)}
    commands = []
    day = start
    while day <= end:
        identity = 'meal-plan-' + day.isoformat()
        if identity not in existing_ids:
            variant = 'A' if (day - ANCHOR).days % 2 == 0 else 'B'
            payload = copy.deepcopy(templates[variant])
            payload['date'] = day.isoformat()
            for meal in payload['meals']:
                if meal['id'] == 'recovery-snack':
                    meal['localTime'] = '22:00' if day.weekday() == 3 else '21:30'
            commands.append(dict(command_id='default-rotation-v1-' + day.isoformat(),
                                 entity_type='meal_plans', entity_id=identity,
                                 expected_revision=0, operation='upsert', payload=payload))
        day += timedelta(days=1)
    return commands

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--end', type=date.fromisoformat, default=END)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    print(json.dumps(generate(json.loads((root/'plan/meal-plan-commands.json').read_text()),end=args.end),indent=2))
