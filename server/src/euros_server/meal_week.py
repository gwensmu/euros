"""Weekly meal drafts for agents: one proposal call, one atomic apply.

Drafts are server-side proposals, not synced records. Applying a draft writes
meal_plans and prep activities through the same revision-checked command path
as the app. Planning never logs food, deducts a pantry or creates purchases.
"""
import copy
import hashlib
import json
from datetime import date, datetime, timedelta, timezone

from .catalog import Catalog, SLOTS
from .domain import MACROS, DomainError, canonical
from .localtime import ZONE, local_clock, local_day, local_today
from .meal_planning import preview
from .meals import MEAL_FLAGS, food_grams, meal_flag, parse_week, snapshot_item
from .nutrition_policy import TIERS, resolve, tier_for, training_on
from .portions import balance

POLICY_VERSION = '2026-11-11-v3'
# Rotation stays continuous with the accepted nutrition-v2 plans.
ROTATION_EPOCH = date(2026, 10, 22)
# Last date covered by the saved training schedule; later days fall back to the weekly pattern.
# Includes the accepted recurring extension and January 3–13 taper. An empty
# date inside this horizon is a rest day, not a gap to fill from a recurrence.
CONFIRMED_THROUGH = date(2027, 1, 13)
COMPETITION_DAY = date(2027, 1, 13)
PROVISIONAL_SESSIONS = {1: [('bjj', '18:30')], 3: [('bjj', '18:30')],
                        5: [('bjj', '10:00')], 6: [('weights', '11:00')]}
DEFAULT_CLOCKS = {'breakfast': '08:00', 'lunch': '12:30', 'dinner': '18:30', 'recovery-snack': '20:30'}
PREP_WEEKDAYS = (2, 6)
PREP_CLOCK = '16:30'
PREP_DAYS_COVERED = 3
PREP_MAX_MEALS = 4
DRAFT_TTL = timedelta(days=7)
PROTEIN_SHORTFALL_G = 8
WHEY_TOP_UP_G = 25


# --- Choosing templates ------------------------------------------------------------------

def _main_protein(template, foods):
    proteins = [i for i in template['items'] if foods[i['food_id']]['role'] == 'protein']
    return max(proteins, key=lambda i: i['grams'])['food_id'] if proteins else None


def _interleave(options, catalog):
    """Round-robin across main proteins so consecutive rotations vary (chicken, salmon, turkey...)."""
    groups = {}
    for identity in options:
        groups.setdefault(_main_protein(catalog['templates'][identity], catalog['foods']), []).append(identity)
    ordered, queues = [], [list(v) for _, v in sorted(groups.items(), key=lambda kv: str(kv[0]))]
    while any(queues):
        for q in queues:
            if q:
                ordered.append(q.pop(0))
    return ordered


def _options(catalog, slot, avoid):
    """Active rotation templates for a slot whose foods are all active, split by avoided foods."""
    foods, clean, blocked = catalog['foods'], [], []
    for identity, t in sorted(catalog['templates'].items()):
        ids = {i['food_id'] for i in t['items']}
        if slot not in t['slots'] or not t['active'] or t['use_up'] or not all(foods[f]['active'] for f in ids):
            continue
        (blocked if ids & avoid else clean).append(identity)
    return _interleave(clean, catalog), _interleave(blocked, catalog)


def _choose(slot, rotation, avoid, catalog, warnings, day, taken=()):
    """Rotation pick for a slot; avoided foods move to the next clean option, else are dropped."""
    clean, blocked = _options(catalog, slot, avoid)
    if clean:
        # Avoid repeating a template or its main protein within the same day.
        main = lambda o: _main_protein(catalog['templates'][o], catalog['foods'])
        preferred = [o for o in clean if o not in taken and main(o) not in taken] or [o for o in clean if o not in taken] or clean
        return preferred[rotation % len(preferred)], set()
    if blocked:
        default = blocked[rotation % len(blocked)]
        dropped = avoid & {i['food_id'] for i in catalog['templates'][default]['items']}
        warnings.append(f'{day} {slot}: no option without {", ".join(sorted(dropped))}; dropped from {catalog["templates"][default]["title"]}')
        return default, dropped
    warnings.append(f'{day} {slot}: no active template; meal omitted')
    return None, set()


# --- Building one day ------------------------------------------------------------------

def _meal(template_id, catalog, slot, clock, dropped=()):
    t, foods = catalog['templates'][template_id], catalog['foods']
    items = [snapshot_item(i['food_id'], foods[i['food_id']], i['grams'], **{k: i[k] for k in ('min_g', 'max_g') if k in i})
             for i in t['items'] if i['food_id'] not in dropped]
    return dict(id=slot, title=t['title'], localTime=clock, appliesWhen='every_day', template_id=template_id,
                template_revision=t['revision'], **{f: t[f] for f in MEAL_FLAGS}, items=items,
                sourceRef=', '.join(sorted({foods[i['food_id']]['source'] for i in items})))


def _calculate(m, foods):
    # Bounds are balancing inputs, not part of the saved meal.
    for i in m['items']:
        i.pop('min_g', None), i.pop('max_g', None)
    m.update({n: round(sum(i['per100g'][n] * i['grams'] / 100 for i in m['items']), 1) for n in MACROS})
    m['foods'] = [f"{i['grams']:g} g {foods[i['food_id']]['name']}" for i in m['items']]
    return m


def _training(records, day):
    saved = training_on(records, day)
    sessions = [(r['payload']['kind'], local_clock(r['payload']['scheduled_at'])) for r in saved]
    snapshot = [dict(id=r['entity_id'], scheduled_at=r['payload']['scheduled_at'], kind=r['payload']['kind'],
                     status=r['payload']['status']) for r in saved]
    if day > CONFIRMED_THROUGH and not saved:
        return PROVISIONAL_SESSIONS.get(day.weekday(), []), 'provisional-weekly-pattern', snapshot
    return sessions, 'saved-schedule', snapshot


def _day_tier(day, sessions, basis):
    tier = tier_for(k for k, _ in sessions)
    # Countdown anchor: food uses a training tier while the assigned event day/time
    # is still unknown. This is a meal assumption, never an authored workout.
    if day == COMPETITION_DAY and not sessions:
        return 'bjj', 'provisional-competition-day'
    return tier, basis


def _meal_clocks(day, sessions):
    """Meal times around the day's training: earlier meals for morning sessions, a pre-training
    snack 90 minutes before BJJ, and dinner moved off late classes."""
    clocks = dict(DEFAULT_CLOCKS)
    bjj = next((t for k, t in sessions if k == 'bjj'), None)
    if sessions and min(t for _, t in sessions) < '13:00':
        clocks.update(breakfast='07:30', lunch='13:00')
    if bjj:
        clocks['pre-training'] = (datetime.combine(day, datetime.strptime(bjj, '%H:%M').time()) - timedelta(minutes=90)).strftime('%H:%M')
        if bjj >= '17:00':
            # Dinner before late classes; no invented training finish or post-session time.
            clocks['dinner'] = '17:30' if bjj >= '20:00' else '20:30'
            clocks['recovery-snack'] = '21:00' if bjj < '20:00' else '15:00'
    return clocks


def _rotation(day):
    # Breakfast rotates daily; lunch and dinner hold for two days so one batch covers both.
    offset = (day - ROTATION_EPOCH).days
    return {'breakfast': offset, 'lunch': offset // 2, 'dinner': offset // 2 + 1, 'recovery-snack': offset, 'pre-training': offset}


def _pick_meals(day, clocks, swaps, avoid, catalog, warnings):
    """One meal per slot: a requested swap, else the rotation pick, minus avoided foods."""
    rotation, meals, chosen = _rotation(day), [], set()
    for slot, clock in clocks.items():
        if slot in swaps:
            template, dropped = swaps[slot], set()
        else:
            template, dropped = _choose(slot, rotation[slot], avoid, catalog, warnings, day, taken=chosen)
        if template is None:
            continue
        chosen |= {template, _main_protein(catalog['templates'][template], catalog['foods'])}
        m = _meal(template, catalog, slot, clock, dropped)
        if not m['items']:
            warnings.append(f'{day} {slot}: omitted, every item avoided')
            continue
        meals.append(m)
    for slot in swaps:
        if slot not in clocks:
            warnings.append(f'{day} {slot}: no such meal that day; swap ignored')
    return meals


def _balanced(meals, catalog, targets, avoid, warnings, day):
    """Scale portions toward targets; add whey to the snack only when protein is still short."""
    foods = catalog['foods']
    totals = balance(meals, foods, targets)
    short = float(targets['proteinG']) - totals['proteinG']
    snack = next((m for m in meals if m['id'] == 'recovery-snack'), None)
    if short > PROTEIN_SHORTFALL_G:
        if snack is not None and 'whey' not in avoid and foods.get('whey', {}).get('active'):
            snack['items'].append(snapshot_item('whey', foods['whey'], WHEY_TOP_UP_G))
            snack['title'] += ' + whey'
            totals = balance(meals, foods, targets)
        if float(targets['proteinG']) - totals['proteinG'] > PROTEIN_SHORTFALL_G:
            warnings.append(f"{day}: protein {totals['proteinG']:.0f} g, below about {float(targets['proteinG']):g} g")
    for m in meals:
        _calculate(m, foods)
    return sorted(meals, key=lambda m: m['localTime'])


# Fields of the retired two-variant plans; dropped when a day is redrafted.
RETIRED_FIELDS = ('maintenance_meals', 'maintenance_planned_totals')


def _build_day(day, records, old, swaps, avoid, catalog, today):
    """One dated plan, balanced to the day's target.

    The target is whatever the nutrition policy says the day's budget is; drafting never asks
    why (training tier, weight phase). The plan keeps the tier it was drafted from as its
    nutrition_targets, which is the policy's input, not a second answer.
    """
    sessions, basis, snapshot = _training(records, day)
    tier, basis = _day_tier(day, sessions, basis)
    target = resolve(records, str(day), TIERS[tier], today=str(today))['targets']
    warnings = []
    meals = _pick_meals(day, _meal_clocks(day, sessions), swaps, avoid, catalog, warnings)
    meals = _balanced(meals, catalog, target, avoid, warnings, day)
    # Stable IDs keep existing links; preserve unrelated payload fields from the saved plan.
    payload = {k: v for k, v in copy.deepcopy(old['payload']).items() if k not in RETIRED_FIELDS} if old else {}
    payload.update(date=str(day), reference_day=tier, nutrition_targets=dict(TIERS[tier]), meals=meals,
                   policy_version=POLICY_VERSION, training_basis=basis, training_snapshot=snapshot)
    payload['planned_totals'] = preview(payload)['planned_totals']
    return payload, target, sessions, warnings


def _day_summary(day, payload, target, sessions, old):
    previous = {m['id']: m['title'] for m in (old['payload']['meals'] if old else [])}
    replaces = lambda m: {'replaces': previous[m['id']]} if previous.get(m['id']) not in (None, m['title']) else {}
    return dict(date=str(day), weekday=day.strftime('%a'), tier=payload['reference_day'],
                training=[f'{k} {t}' for k, t in sessions], training_basis=payload['training_basis'],
                targets=target, planned_totals=payload['planned_totals'],
                meals=[dict(id=m['id'], time=m['localTime'], title=m['title'], template=m['template_id'],
                            foods=m['foods'], kcal=m['kcal'], proteinG=m['proteinG'], **replaces(m))
                       for m in payload['meals']],
                status='update' if old else 'new')


# --- Prep ------------------------------------------------------------------------------

def _prep_clock(dinner):
    if dinner and any(i['food_id'] == 'salmon' for i in dinner.get('items', [])):
        # Cook salmon shortly before that day's dinner, wherever training moved it.
        return min('18:00', (datetime.strptime(dinner['localTime'], '%H:%M') - timedelta(minutes=30)).strftime('%H:%M'))
    return PREP_CLOCK


def _covered_meals(day, clock, plans, saved_plans, templates):
    """The next few batch meals a prep cooks for, starting after the prep itself."""
    covered_dates = [str(day + timedelta(days=i)) for i in range(PREP_DAYS_COVERED)]
    covered = []
    for d in covered_dates:
        plan = plans.get(d) or saved_plans.get(d) or {}
        for m in plan.get('meals', []):
            if len(covered) >= PREP_MAX_MEALS or not meal_flag(m, templates, 'batch'):
                continue
            if d == str(day) and m['localTime'] < clock:
                continue
            covered.append((d, m))
    return covered_dates, covered


def _prep_instructions(cooked, foods):
    # Proteins as portions, sides as weight, in a stable order.
    name = lambda k: foods[k]['name'].replace('cooked ', '')
    proteins = sorted(k for k in cooked if foods[k]['role'] == 'protein')
    parts = [f"{cooked[k]['meals']} {name(k)} portion{'s' if cooked[k]['meals'] > 1 else ''} (about {cooked[k]['grams']:g} g)"
             for k in proteins]
    parts += [f"about {cooked[k]['grams']:g} g {name(k)}" for k in sorted(cooked) if k not in proteins]
    return 'Cook ' + ', '.join(parts) + ' (cooked weights). Portion using the dated meal plan; refrigerate promptly.'


def _prep(day, plans, saved_plans, existing, catalog):
    """Prep instructions follow the batch meals the prep actually covers."""
    foods, templates = catalog['foods'], catalog['templates']
    clock = _prep_clock(next((m for m in plans.get(str(day), {}).get('meals', []) if m['id'] == 'dinner'), None))
    covered_dates, covered = _covered_meals(day, clock, plans, saved_plans, templates)
    if not covered:
        return None
    cooked = food_grams((m for _, m in covered), include=lambda f: foods.get(f, {}).get('batch_cook'))
    current = existing.get(day)
    base = copy.deepcopy(current['payload']) if current else dict(kind='prep', title='Prep for the next few meals',
                                                                   status='pending', time_zone=str(ZONE), plan_revision='weekly-draft')
    if not current:
        base['scheduled_at'] = datetime.combine(day, datetime.strptime(clock, '%H:%M').time(), ZONE).isoformat()
    base.update(prescription={**base.get('prescription', {}), 'instructions': _prep_instructions(cooked, foods)},
                covered_dates=covered_dates,
                covered_meals=[dict(date=d, meal_id=m['id'], title=m['title'], localTime=m['localTime']) for d, m in covered])
    return dict(entity_id=current['entity_id'] if current else f'prep-{day}',
                base_revision=current['revision'] if current else 0, payload=base)


def _draft_preps(plans, saved, records, catalog):
    """Prep activities for the drafted prep weekdays; completed or skipped prep is kept."""
    existing = {}
    for r in records:
        if r['entity_type'] == 'activities' and r['payload'].get('kind') == 'prep':
            existing.setdefault(local_day(r['payload']['scheduled_at']), r)
    payloads = {d: p['payload'] for d, p in plans.items()}
    saved_payloads = {d: r['payload'] for d, r in saved.items()}
    preps, summary = [], []
    for d in plans:
        day = date.fromisoformat(d)
        if day.weekday() not in PREP_WEEKDAYS:
            continue
        current = existing.get(day)
        if current and current['payload']['status'] != 'pending':
            summary.append(dict(date=d, status=f"kept ({current['payload']['status']})"))
            continue
        prep = _prep(day, payloads, saved_payloads, existing, catalog)
        if prep:
            preps.append(prep)
            summary.append(dict(date=d, time=local_clock(prep['payload']['scheduled_at']),
                                instructions=prep['payload']['prescription']['instructions'],
                                covers=[f"{c['date']} {c['meal_id']}" for c in prep['payload']['covered_meals']],
                                status='update' if prep['base_revision'] else 'new'))
    return preps, summary


# --- Requests ----------------------------------------------------------------------------

def _parse_swaps(swaps, days, catalog):
    parsed, templates = {}, catalog['templates']
    for s in swaps or []:
        if not isinstance(s, dict) or set(s) != {'date', 'meal_id', 'template'}:
            raise DomainError('invalid_swap', required=['date', 'meal_id', 'template'])
        if s['meal_id'] not in SLOTS:
            raise DomainError('invalid_swap_meal', meal_id=s['meal_id'], allowed=list(SLOTS))
        t = templates.get(s['template'])
        if t is None:
            raise DomainError('invalid_swap_template', template=s['template'], allowed=sorted(templates))
        if s['meal_id'] not in t['slots']:
            raise DomainError('template_not_for_meal', template=s['template'], meal_id=s['meal_id'], slots=t['slots'])
        inactive = [i['food_id'] for i in t['items'] if not catalog['foods'][i['food_id']]['active']]
        if inactive:
            raise DomainError('template_uses_inactive_food', template=s['template'], foods=inactive)
        if s['date'] not in {str(d) for d in days}:
            raise DomainError('swap_date_not_drafted', date=s['date'])
        parsed.setdefault(s['date'], {})[s['meal_id']] = s['template']
    return parsed


class MealWeeks:
    def __init__(self, store):
        self.store, self.catalog = store, Catalog(store)
        with store.connect() as db:
            db.execute('CREATE TABLE IF NOT EXISTS meal_week_drafts '
                       '(draft_id TEXT PRIMARY KEY, created_at TEXT NOT NULL, body TEXT NOT NULL)')

    def draft(self, week_start, dates=None, swaps=None, avoid_foods=None, today=None):
        _, days = parse_week(week_start, dates)
        catalog = self.catalog.snapshot()
        avoid = set(avoid_foods or [])
        if not avoid <= set(catalog['foods']):
            raise DomainError('unknown_food', unknown=sorted(avoid - set(catalog['foods'])), allowed=sorted(catalog['foods']))
        swaps = _parse_swaps(swaps, days, catalog)
        today = today or local_today()
        records = self.store.records()['records']
        saved = {r['payload']['date']: r for r in records if r['entity_type'] == 'meal_plans'}
        skipped, plans, summary, warnings = [], {}, [], []
        tombstoned = self._deleted_plans()
        for day in days:
            key = f'meal-plan-{day}'
            if day <= today:
                skipped.append(dict(date=str(day), reason='today or past; revise remaining meals with apply_command'))
                continue
            if key in tombstoned:
                skipped.append(dict(date=str(day), reason='plan deleted; not recreated'))
                continue
            old = saved.get(str(day))
            payload, target, sessions, day_warnings = _build_day(day, records, old, swaps.get(str(day), {}), avoid, catalog, today)
            warnings += day_warnings
            plans[str(day)] = dict(entity_id=key, base_revision=old['revision'] if old else 0, payload=payload)
            summary.append(_day_summary(day, payload, target, sessions, old))
        if not plans:
            raise DomainError('nothing_to_draft', skipped=skipped)
        preps, prep_summary = _draft_preps(plans, saved, records, catalog)
        draft_id = self._store_draft(dict(week_start=week_start, plans=list(plans.values()), preps=preps))
        return dict(draft_id=draft_id, saved=False, week_start=week_start, days=summary, preps=prep_summary,
                    skipped=skipped, warnings=warnings,
                    next_step='Show the user this week. Redraft with swaps/avoid_foods for changes; call apply_week only after they accept.')

    def _deleted_plans(self):
        with self.store.connect() as db:
            return {r['entity_id'] for r in db.execute(
                "SELECT entity_id FROM records WHERE entity_type='meal_plans' AND deleted=1")}

    def _store_draft(self, body):
        """Content-addressed, so redrafting the same week returns the same ID; expired drafts are swept."""
        draft_id = hashlib.sha256(canonical(body).encode()).hexdigest()[:16]
        now = datetime.now(timezone.utc)
        with self.store.connect() as db:
            db.execute('DELETE FROM meal_week_drafts WHERE created_at<?', ((now - DRAFT_TTL).isoformat(),))
            db.execute('INSERT OR REPLACE INTO meal_week_drafts VALUES (?,?,?)', (draft_id, now.isoformat(), canonical(body)))
        return draft_id

    def apply(self, draft_id):
        if not isinstance(draft_id, str) or not draft_id:
            raise DomainError('invalid_draft_id')
        with self.store.connect() as db:
            row = db.execute('SELECT body FROM meal_week_drafts WHERE draft_id=?', (draft_id,)).fetchone()
        if row is None:
            raise DomainError('draft_not_found', 404, hint='Drafts expire after 7 days; call draft_week again.')
        body = json.loads(row['body'])
        commands = [dict(command_id=f'week-{draft_id}-{p["entity_id"]}', entity_type=kind, entity_id=p['entity_id'],
                         expected_revision=p['base_revision'], operation='upsert', payload=p['payload'])
                    for kind, items in (('meal_plans', body['plans']), ('activities', body['preps'])) for p in items]
        try:
            with self.store.connect() as db:
                db.execute('BEGIN IMMEDIATE')
                results = [self.store.command(c, _db=db) for c in commands]
        except DomainError as exc:
            if exc.body.get('error') in ('revision_conflict', 'record_deleted'):
                raise DomainError('draft_stale', 409, entity_id=exc.body.get('current', {}).get('entity_id'),
                                  hint='A plan or prep changed after drafting. Nothing was saved; call draft_week again.') from None
            raise
        return dict(saved=True, week_start=body['week_start'],
                    meal_plans=[dict(date=r['record']['payload']['date'], revision=r['record']['revision'])
                                for r in results if r['record']['entity_type'] == 'meal_plans'],
                    preps=[dict(entity_id=r['record']['entity_id'], revision=r['record']['revision'])
                           for r in results if r['record']['entity_type'] == 'activities'])
