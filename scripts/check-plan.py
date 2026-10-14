#!/usr/bin/env python3
"""Validate authored plan data without filling gaps or generating user records."""
import argparse
from datetime import date, datetime, time
import json
import math
from pathlib import Path
import re


def validate_plan(plan):
    errors, unresolved = [], []

    def issue(path, message):
        errors.append(f"{path}: {message}")

    def day(value, path, required=False):
        if value is None:
            if required:
                issue(path, "required ISO date (YYYY-MM-DD)")
            else:
                unresolved.append(f"{path}: choose a date")
            return None
        if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
            issue(path, "expected ISO date (YYYY-MM-DD)")
            return None
        try:
            return date.fromisoformat(value)
        except ValueError:
            issue(path, "invalid calendar date")
            return None

    def walk(value, path):
        if value is None:
            unresolved.append(f"{path}: supply a value when configured; remains unset")
        elif isinstance(value, bool):
            return
        elif isinstance(value, (int, float)):
            if (isinstance(value, float) and not math.isfinite(value)) or value < 0:
                issue(path, "quantity must be finite and nonnegative")
        elif isinstance(value, dict):
            for key, child in value.items():
                walk(child, f"{path}.{key}")
            if 'min' in value and 'max' in value:
                lo, hi = value['min'], value['max']
                if (isinstance(lo, (int, float)) and not isinstance(lo, bool)
                        and isinstance(hi, (int, float)) and not isinstance(hi, bool)
                        and lo > hi):
                    issue(path, "min must not exceed max")
        elif isinstance(value, list):
            for i, child in enumerate(value):
                walk(child, f"{path}[{i}]")

    if not isinstance(plan, dict):
        return ["plan: expected object"], []
    walk(plan, 'plan')
    if type(plan.get('schemaVersion')) is not int or plan['schemaVersion'] != 1:
        issue('schemaVersion', 'expected version 1')
    if not isinstance(plan.get('revision'), str) or not plan['revision'].strip():
        issue('revision', 'nonempty revision required')
    event = plan.get('event')
    event_day = day(event.get('date'), 'event.date', True) if isinstance(event, dict) else None
    if not isinstance(event, dict):
        issue('event', 'required object')
    phases = plan.get('phases')
    if not isinstance(phases, list):
        issue('phases', 'required list')
        phases = []
    seen, intervals = set(), []
    for i, phase in enumerate(phases):
        path = f'phases[{i}]'
        if not isinstance(phase, dict):
            issue(path, 'expected object')
            continue
        identity = phase.get('id')
        if not isinstance(identity, str) or not identity.strip():
            issue(path + '.id', 'nonempty ID required')
        elif identity in seen:
            issue(path + '.id', f'duplicate phase ID {identity!r}')
        else:
            seen.add(identity)
        start = day(phase.get('start'), path + '.start')
        end = day(phase.get('end'), path + '.end')
        if start and end:
            if start > end:
                issue(path, 'start must not follow end')
            else:
                intervals.append((start, end, path))
        if event_day and any(d and d >= event_day for d in (start, end)):
            issue(path, 'camp phase must precede event date')
    intervals.sort()
    for i, (start, end, path) in enumerate(intervals):
        for previous_start, previous_end, previous_path in intervals[:i]:
            if start <= previous_end:
                issue(path, f'overlaps {previous_path} (dates are inclusive)')
    goal = plan.get('goal', {})
    if isinstance(goal, dict) and 'baselineDate' in goal:
        day(goal['baselineDate'], 'goal.baselineDate')
    weigh = plan.get('weeklyWeighIn', {})
    if isinstance(weigh, dict) and weigh.get('time') is not None:
        value = weigh['time']
        try:
            if not isinstance(value, str) or not re.fullmatch(r'\d{2}:\d{2}', value):
                raise ValueError()
            time.fromisoformat(value)
        except ValueError:
            issue('weeklyWeighIn.time', 'expected valid HH:MM local time')
    if 'scheduledActivities' not in plan or plan['scheduledActivities'] == []:
        unresolved.append('scheduledActivities: author dated/timed activities before production scheduling')
    else:
        activities = plan['scheduledActivities']
        if not isinstance(activities, list):
            issue('scheduledActivities', 'expected list')
        else:
            ids = set()
            for i, activity in enumerate(activities):
                path = f'scheduledActivities[{i}]'
                if not isinstance(activity, dict):
                    issue(path, 'expected object')
                    continue
                identity = activity.get('id')
                if not isinstance(identity, str) or not identity.strip():
                    issue(path + '.id', 'nonempty ID required')
                elif identity in ids:
                    issue(path + '.id', 'duplicate activity ID')
                else:
                    ids.add(identity)
                issue_text = validate_timestamp(activity.get('scheduled_at'))
                if issue_text:
                    issue(path + '.scheduled_at', issue_text)
    authored = plan.get('unresolved', [])
    if isinstance(authored, list):
        unresolved.extend(f'Author decision: {item}' for item in authored)
    return errors, list(dict.fromkeys(unresolved))


def validate_timestamp(value):
    if not isinstance(value, str) or not re.match(r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}', value):
        return 'required ISO date/time with UTC offset'
    try:
        parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
        if parsed.utcoffset() is None:
            return 'UTC offset required'
    except ValueError:
        return 'invalid calendar date/time'
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('path', nargs='?', type=Path,
                        default=Path(__file__).resolve().parents[1] / 'plan/euros.json')
    args = parser.parse_args()
    try:
        plan = json.loads(args.path.read_text())
    except (OSError, ValueError) as exc:
        print(f'ERROR: cannot read plan: {exc}')
        return 1
    errors, unresolved = validate_plan(plan)
    for error in errors:
        print(f'ERROR: {error}')
    for item in unresolved:
        print(f'UNRESOLVED: {item}')
    print(f'{"INVALID" if errors else "VALID"}: {len(errors)} error(s), '
          f'{len(unresolved)} unresolved item(s). No records generated.')
    return int(bool(errors))


if __name__ == '__main__':
    raise SystemExit(main())
