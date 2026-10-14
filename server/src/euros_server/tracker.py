"""Source-owned tracker snapshots, isolated from manual records and their change feed."""
import hashlib
import json
from datetime import date, datetime, timezone, timedelta
from zoneinfo import ZoneInfo
from .domain import DomainError, canonical, instant, MAX_SQLITE_INTEGER

POLAR = 'fi.polar.polarflow'
ZONE = ZoneInfo('America/Chicago')
MAX_SAMPLES = 20000
PERSONAL_ZONE_PROFILE = {
    'id': 'personal-polar-2026-10-14',
    'boundaries_bpm': [95, 114, 133, 152, 171, 190],
    'provenance': 'User-confirmed Polar zone screenshot, October 14, 2026; screenshot not retained in repository',
    'convention': 'lower inclusive, upper exclusive; below 95 and at/above 190 reported separately',
    'estimate': True,
}


def text(value, field, limit=500):
    if not isinstance(value, str) or not value.strip() or len(value) > limit:
        raise DomainError('invalid_tracker_field', field=field)
    return value


def integer(value, field, low=0, high=MAX_SQLITE_INTEGER-1):
    if isinstance(value, bool) or not isinstance(value, int) or not low <= value <= high:
        raise DomainError('invalid_tracker_field', field=field)
    return value


def validate(record):
    if not isinstance(record, dict):
        raise DomainError('invalid_tracker_record')
    text(record.get('source_key'), 'source_key')
    integer(record.get('expected_revision'), 'expected_revision')
    if type(record.get('deleted')) is not bool:
        raise DomainError('invalid_tracker_field', field='deleted')
    observed = instant(record.get('observed_at'), 'observed_at')
    if observed > datetime.now(timezone.utc) + timedelta(minutes=5):
        raise DomainError('future_tracker_observation')
    if record['deleted']:
        return
    if record.get('source_package') != POLAR:
        raise DomainError('unsupported_tracker_source')
    text(record.get('source_id'), 'source_id')
    if record.get('source_title') is not None: text(record['source_title'], 'source_title')
    if record.get('exercise_type') is not None: integer(record['exercise_type'], 'exercise_type', 0, 10000)
    instant(record.get('source_modified_at'), 'source_modified_at')
    start, end = instant(record.get('start_at'), 'start_at'), instant(record.get('end_at'), 'end_at')
    if not 0 < (end-start).total_seconds() <= 86400:
        raise DomainError('invalid_tracker_interval')
    if record.get('activity_id') is not None:
        text(record['activity_id'], 'activity_id', 200)
    samples = record.get('heart_rate_samples')
    if not isinstance(samples, list) or len(samples) > MAX_SAMPLES:
        raise DomainError('invalid_tracker_samples')
    last = None
    for sample in samples:
        if not isinstance(sample, dict) or sample.get('source_package') != POLAR:
            raise DomainError('invalid_tracker_sample_source')
        at = instant(sample.get('at'), 'sample_at')
        if not start <= at <= end or (last is not None and at <= last):
            raise DomainError('unordered_or_outside_tracker_samples')
        last = at
        integer(sample.get('bpm'), 'bpm', 1, 300)
        text(sample.get('source_id'), 'sample_source_id')
    canonical(record)


def sport_identity(payload):
    # Personal convention: Polar Judo is BJJ; Health Connect exports it as martial arts (44).
    mapped = payload.get('source_package') == POLAR and payload.get('exercise_type') == 44
    return {'display_name': 'BJJ' if mapped else (payload.get('source_title') or 'Polar workout'),
            'sport': 'bjj' if mapped else None,
            'sport_mapping': 'user: Polar Judo / Health Connect martial arts means BJJ' if mapped else None}


def metrics(payload, boundaries=None):
    """Zero-order hold only between adjacent samples <=60s; never bridge missing HR."""
    if boundaries is not None:
        if not isinstance(boundaries, list) or not 1 <= len(boundaries) <= 10:
            raise DomainError('invalid_hr_boundaries')
        for value in boundaries:
            integer(value, 'hr_boundary', 1, 300)
        if boundaries != sorted(set(boundaries)):
            raise DomainError('invalid_hr_boundaries')
    samples = payload['heart_rate_samples']
    start, end = instant(payload['start_at'], 'start_at'), instant(payload['end_at'], 'end_at')
    duration = (end-start).total_seconds()
    intervals = []
    total = weighted = 0.0
    bands = [0.0] * (len(boundaries)+1) if boundaries is not None else None
    zone_seconds = [0.0] * 7
    segments = []
    for a, b in zip(samples, samples[1:]):
        left, right = instant(a['at'], 'at'), instant(b['at'], 'at')
        seconds = (right-left).total_seconds()
        if seconds <= 60:
            total += seconds
            weighted += seconds*a['bpm']
            zone_seconds[sum(a['bpm'] >= cut for cut in PERSONAL_ZONE_PROFILE['boundaries_bpm'])] += seconds
            segments.append((left, right, a['bpm']))
            if bands is not None:
                bands[sum(a['bpm'] >= cut for cut in boundaries)] += seconds
    for offset in range(0, int(duration)+1, 300):
        left, right = start+timedelta(seconds=offset), min(end, start+timedelta(seconds=offset+300))
        if left >= right:
            break
        covered = bpm_sum = 0.0
        peaks = []
        for a, b, bpm in segments:
            seconds = max(0, (min(b, right)-max(a, left)).total_seconds())
            if seconds:
                covered += seconds; bpm_sum += seconds*bpm; peaks.append(bpm)
        intervals.append({'start_offset_s': offset, 'duration_s': (right-left).total_seconds(),
                          'covered_seconds': covered, 'average_bpm': round(bpm_sum/covered, 2) if covered else None,
                          'peak_covered_bpm': max(peaks) if peaks else None})
    # Min/max pairs per sequential bucket preserve extrema, capped at 600 points.
    stride = max(1, (len(samples)+299)//300)
    curve = []
    for i in range(0, len(samples), stride):
        bucket = samples[i:i+stride]
        extrema = sorted({min(range(len(bucket)), key=lambda j: bucket[j]['bpm']),
                          max(range(len(bucket)), key=lambda j: bucket[j]['bpm'])})
        curve.extend({'at': bucket[j]['at'], 'bpm': bucket[j]['bpm']} for j in extrema)
    return {'duration_seconds': duration, 'sample_count': len(samples), 'covered_seconds': total,
            'coverage_fraction': round(total/duration, 6), 'max_bridged_gap_seconds': 60,
            'average_bpm': round(weighted/total, 2) if total else None,
            'peak_bpm': max((s['bpm'] for s in samples), default=None),
            'sample_average_bpm': round(sum(s['bpm'] for s in samples)/len(samples), 2) if samples else None,
            'curve': curve, 'curve_method': 'ordered_bucket_min_max', 'curve_downsampled': len(curve)<len(samples),
            'intervals': intervals, 'interval_seconds': 300,
            'hr_zones': {**PERSONAL_ZONE_PROFILE, 'zones': [
                {'name': f'Zone {i}', 'lower_bpm': PERSONAL_ZONE_PROFILE['boundaries_bpm'][i-1],
                 'upper_bpm_exclusive': PERSONAL_ZONE_PROFILE['boundaries_bpm'][i], 'covered_seconds': zone_seconds[i]}
                for i in range(1, 6)], 'below_range_seconds': zone_seconds[0],
                'above_range_seconds': zone_seconds[6], 'uncovered_seconds': duration-total},
            'time_bands': None if bands is None else {'boundaries_bpm': boundaries, 'covered_seconds': bands,
                'convention': 'lower inclusive, upper exclusive; not Polar zones'},
            'method': 'time-weighted previous-sample hold; only adjacent gaps <=60s; no leading/trailing extrapolation'}


class TrackerStore:
    def __init__(self, store):
        self.store = store
        with store.connect() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS tracker_workouts (
                    source_key TEXT PRIMARY KEY, revision INTEGER NOT NULL, deleted INTEGER NOT NULL,
                    payload TEXT NOT NULL, observed_at TEXT NOT NULL, received_at TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS tracker_commands (
                    command_id TEXT PRIMARY KEY, digest TEXT NOT NULL, result TEXT NOT NULL);
            ''')

    @staticmethod
    def header(row):
        return {'source_key': row['source_key'], 'revision': row['revision'], 'deleted': bool(row['deleted']),
                'observed_at': row['observed_at'], 'received_at': row['received_at'],
                'content_hash': hashlib.sha256(row['payload'].encode()).hexdigest()}

    def ingest(self, command):
        if not isinstance(command, dict): raise DomainError('invalid_tracker_import')
        cid = text(command.get('command_id'), 'command_id', 200)
        records = command.get('records')
        if not isinstance(records, list) or not 1 <= len(records) <= 20:
            raise DomainError('invalid_tracker_batch')
        for record in records: validate(record)
        if len({r['source_key'] for r in records}) != len(records): raise DomainError('duplicate_tracker_key')
        raw = canonical(command)
        if len(raw.encode()) > 4194304: raise DomainError('request_too_large', 413)
        digest = hashlib.sha256(raw.encode()).hexdigest()
        with self.store.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            prior = db.execute('SELECT * FROM tracker_commands WHERE command_id=?', (cid,)).fetchone()
            if prior:
                if prior['digest'] != digest: raise DomainError('idempotency_key_reused', 409)
                return json.loads(prior['result'])
            result = []
            for incoming in records:
                key = incoming['source_key']
                row = db.execute('SELECT * FROM tracker_workouts WHERE source_key=?', (key,)).fetchone()
                if incoming['expected_revision'] != (row['revision'] if row else 0):
                    raise DomainError('tracker_revision_conflict', 409, current=self.header(row) if row else None, source_key=key)
                if row and instant(incoming['observed_at'], 'observed_at') < instant(row['observed_at'], 'observed_at'):
                    raise DomainError('stale_tracker_observation', 409, current=self.header(row), source_key=key)
                if incoming['deleted'] and row is None: raise DomainError('unknown_tracker_workout', 404)
                payload = json.loads(row['payload']) if incoming['deleted'] else {k:v for k,v in incoming.items() if k not in ('expected_revision','deleted')}
                if row and not incoming['deleted']:
                    old = json.loads(row['payload'])
                    if (old['source_package'],old['source_id']) != (payload['source_package'],payload['source_id']):
                        raise DomainError('tracker_identity_changed',409)
                    if instant(payload['source_modified_at'],'source_modified_at') < instant(old['source_modified_at'],'source_modified_at'):
                        raise DomainError('stale_tracker_source',409)
                if not incoming['deleted']:
                    for other in db.execute('SELECT source_key,payload FROM tracker_workouts WHERE source_key!=?',(key,)):
                        old = json.loads(other['payload'])
                        if (old['source_package'],old['source_id']) == (payload['source_package'],payload['source_id']):
                            raise DomainError('duplicate_tracker_source_identity',409)
                linked = payload.get('activity_id') if not incoming['deleted'] else None
                if linked:
                    activity = db.execute("SELECT deleted FROM records WHERE entity_type='activities' AND entity_id=?",(linked,)).fetchone()
                    if activity is None or activity['deleted']: raise DomainError('unknown_linked_activity')
                revision = (row['revision'] if row else 0)+1
                received = datetime.now(timezone.utc).isoformat()
                db.execute('INSERT OR REPLACE INTO tracker_workouts VALUES (?,?,?,?,?,?)',
                           (key, revision, int(incoming['deleted']), canonical(payload), incoming['observed_at'], received))
                result.append({'source_key': key, 'revision': revision, 'deleted': incoming['deleted']})
            links = [json.loads(r['payload']).get('activity_id') for r in db.execute('SELECT payload FROM tracker_workouts WHERE deleted=0')]
            links = [link for link in links if link is not None]
            if len(links) != len(set(links)): raise DomainError('tracker_activity_already_linked',409)
            response = {'command_id': cid, 'records': result}
            db.execute('INSERT INTO tracker_commands VALUES (?,?,?)',(cid,digest,canonical(response)))
            return response

    def read(self, key, boundaries=None):
        text(key,'source_key')
        with self.store.connect() as db:
            row = db.execute('SELECT * FROM tracker_workouts WHERE source_key=?',(key,)).fetchone()
        if row is None: return {'workout': None}
        header = self.header(row)
        if row['deleted']: return {'workout': header}
        payload = json.loads(row['payload'])
        return {'workout': {**header, **{k:v for k,v in payload.items() if k != 'heart_rate_samples'},
                            'heart_rate_source_ids': sorted({s['source_id'] for s in payload['heart_rate_samples']}),
                            **sport_identity(payload), 'training_benefit': None, 'polar_zone_definitions': PERSONAL_ZONE_PROFILE,
                            'metrics': metrics(payload,boundaries)}}

    def list(self, date_from, date_to):
        try:
            first,last=date.fromisoformat(date_from),date.fromisoformat(date_to)
            if first.isoformat()!=date_from or last.isoformat()!=date_to or not 0 <= (last-first).days <= 365: raise ValueError()
        except (TypeError, ValueError): raise DomainError('invalid_tracker_date_range') from None
        result=[]
        with self.store.connect() as db:
            rows=db.execute('SELECT * FROM tracker_workouts ORDER BY source_key').fetchall()
        for row in rows:
            payload=json.loads(row['payload'])
            day=instant(payload['start_at'],'start_at').astimezone(ZONE).date()
            if not first<=day<=last: continue
            item=self.header(row)
            item.update({k:payload.get(k) for k in ('source_package','source_id','source_modified_at','start_at','end_at','activity_id','exercise_type','source_title')})
            if not row['deleted']:
                item.update(sport_identity(payload))
                measured=metrics(payload)
                item['metrics']={k:v for k,v in measured.items() if k not in ('curve','intervals','time_bands')}
            result.append(item)
        return {'workouts':result,'date_zone':str(ZONE),'date_basis':'workout start','includes_tombstones':True}
