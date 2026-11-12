"""Server-only planning catalog: foods and meal templates.

Not synced records: the phone never sees the catalog, only the dated meal plans built from it.
Every accepted edit is revisioned with history; saved plans snapshot the values they used.
"""
import json
import re
from datetime import datetime, timezone

from .domain import MACROS, DomainError, canonical, number
from . import catalog_seed

KINDS = ('food', 'template')
ROLES = ('protein', 'carb', 'fat', 'fixed')
BASES = ('cooked', 'raw', 'dry', 'as_sold')
SLOTS = ('breakfast', 'lunch', 'dinner', 'recovery-snack', 'pre-training')
# Purchase fields added after the first seeding; see Catalog._backfill_purchase_units.
BACKFILLED = {'unit', 'staple'}
IDENTIFIER = re.compile(r'^[a-z][a-z0-9_]{0,59}$')


def _text(value, field, maximum=200):
    if not isinstance(value, str) or not 1 <= len(value.strip()) <= maximum:
        raise DomainError('invalid_catalog_field', field=field)
    return value.strip()


def _flag(value, field):
    if not isinstance(value, bool):
        raise DomainError('invalid_catalog_field', field=field)
    return value


def _pack(value):
    """How a countable unit is sold in bulk: a dozen eggs, an 8-pack of tortillas.

    count is how many units one pack holds, so the grocery list can round a count up to
    something a shelf actually offers while still naming the units the user recognises.
    """
    if not isinstance(value, dict) or not {'name', 'count'} <= set(value) <= {'name', 'plural', 'count'}:
        raise DomainError('invalid_catalog_field', field='purchase.unit.pack',
                          required=['name', 'count'], optional=['plural'])
    count = number(value['count'], 'purchase.unit.pack.count', positive=True)
    if int(count) != count or not 2 <= count <= 60:
        raise DomainError('invalid_catalog_field', field='purchase.unit.pack.count')
    name = _text(value['name'], 'purchase.unit.pack.name', 60)
    return dict(name=name, plural=_text(value.get('plural', name + 's'), 'purchase.unit.pack.plural', 60), count=int(count))


def _unit(value):
    """A countable purchase unit: one egg, one banana, one bag/can/tub of a given size.

    grams is the bought grams one unit contributes, in the same form as purchase.form,
    so the grocery list can report counts instead of raw gram totals. An optional pack says
    the unit is only sold in multiples (eggs by the dozen).
    """
    allowed = {'name', 'plural', 'grams', 'pack'}
    if not isinstance(value, dict) or not {'name', 'grams'} <= set(value) <= allowed:
        raise DomainError('invalid_catalog_field', field='purchase.unit', required=['name', 'grams'],
                          optional=['plural', 'pack'])
    grams = number(value['grams'], 'purchase.unit.grams', positive=True)
    if not 1 <= grams <= 5000:
        raise DomainError('invalid_catalog_field', field='purchase.unit.grams')
    name = _text(value['name'], 'purchase.unit.name', 60)
    return dict(name=name, plural=_text(value.get('plural', name + 's'), 'purchase.unit.plural', 60), grams=float(grams),
                **({'pack': _pack(value['pack'])} if 'pack' in value else {}))


def validate_food(p):
    allowed = {'name', 'per100g', 'role', 'basis', 'step_g', 'batch_cook', 'purchase', 'source', 'active'}
    if not isinstance(p, dict) or not {'name', 'per100g', 'role'} <= set(p) <= allowed:
        raise DomainError('invalid_food_fields', required=['name', 'per100g', 'role'], allowed=sorted(allowed))
    per = p['per100g']
    if not isinstance(per, dict) or set(per) != set(MACROS):
        raise DomainError('invalid_catalog_field', field='per100g', required=list(MACROS))
    for k in MACROS:
        if number(per[k], k) > (900 if k == 'kcal' else 100):
            raise DomainError('invalid_catalog_field', field=f'per100g.{k}')
    if p['role'] not in ROLES:
        raise DomainError('invalid_catalog_field', field='role', allowed=list(ROLES))
    purchase = p.get('purchase', {})
    if not isinstance(purchase, dict) or not set(purchase) <= {'form', 'cooked_yield', 'unit', 'staple'}:
        raise DomainError('invalid_catalog_field', field='purchase')
    if 'cooked_yield' in purchase and not 0 < number(purchase['cooked_yield'], 'cooked_yield') <= 5:
        raise DomainError('invalid_catalog_field', field='purchase.cooked_yield')
    out = dict(name=_text(p['name'], 'name'), per100g={k: float(per[k]) for k in MACROS}, role=p['role'],
               basis=p.get('basis', 'cooked'), step_g=p.get('step_g', 5), batch_cook=_flag(p.get('batch_cook', False), 'batch_cook'),
               purchase={**({'form': _text(purchase['form'], 'purchase.form')} if 'form' in purchase else {}),
                         **({'cooked_yield': float(purchase['cooked_yield'])} if 'cooked_yield' in purchase else {}),
                         **({'unit': _unit(purchase['unit'])} if 'unit' in purchase else {}),
                         **({'staple': _flag(purchase['staple'], 'purchase.staple')} if 'staple' in purchase else {})},
               source=_text(p.get('source', 'agent-entered estimate'), 'source'), active=_flag(p.get('active', True), 'active'))
    if out['basis'] not in BASES:
        raise DomainError('invalid_catalog_field', field='basis', allowed=list(BASES))
    if not 1 <= number(out['step_g'], 'step_g', positive=True) <= 200:
        raise DomainError('invalid_catalog_field', field='step_g')
    return out


def validate_template(p, foods):
    allowed = {'title', 'slots', 'items', 'batch', 'use_up', 'active'}
    if not isinstance(p, dict) or not {'title', 'slots', 'items'} <= set(p) <= allowed:
        raise DomainError('invalid_template_fields', required=['title', 'slots', 'items'], allowed=sorted(allowed))
    slots = p['slots']
    if not isinstance(slots, list) or not slots or len(set(slots)) != len(slots) or not set(slots) <= set(SLOTS):
        raise DomainError('invalid_catalog_field', field='slots', allowed=list(SLOTS))
    items = p['items']
    if not isinstance(items, list) or not 1 <= len(items) <= 12:
        raise DomainError('invalid_catalog_field', field='items')
    out_items, seen = [], set()
    for i in items:
        if not isinstance(i, dict) or not {'food_id', 'grams'} <= set(i) <= {'food_id', 'grams', 'min_g', 'max_g'}:
            raise DomainError('invalid_template_item', required=['food_id', 'grams'], optional=['min_g', 'max_g'])
        if i['food_id'] not in foods:
            raise DomainError('unknown_food', unknown=[i['food_id']])
        if i['food_id'] in seen:
            raise DomainError('duplicate_template_food', food_id=i['food_id'])
        seen.add(i['food_id'])
        grams = float(number(i['grams'], 'grams', positive=True))
        low, high = float(number(i.get('min_g', 0), 'min_g')), float(number(i.get('max_g', 2000), 'max_g'))
        if grams > 2000 or not low <= grams <= high:
            raise DomainError('invalid_template_item', food_id=i['food_id'], field='grams')
        out_items.append({'food_id': i['food_id'], 'grams': grams,
                          **({'min_g': low} if 'min_g' in i else {}), **({'max_g': high} if 'max_g' in i else {})})
    return dict(title=_text(p['title'], 'title'), slots=slots, items=out_items, batch=_flag(p.get('batch', False), 'batch'),
                use_up=_flag(p.get('use_up', False), 'use_up'), active=_flag(p.get('active', True), 'active'))


def _seeds():
    """Built-ins in stored form: validated exactly like an agent edit, so stored built-ins and
    edits have one shape (defaults such as unit plurals filled in) and a bad seed fails startup."""
    for identity, raw in catalog_seed.FOODS.items():
        yield 'food', identity, validate_food(raw)
    for identity, raw in catalog_seed.TEMPLATES.items():
        yield 'template', identity, validate_template(raw, catalog_seed.FOODS)


def _write(db, kind, identity, revision, payload, now, only_if_missing=False):
    """Store a catalog revision and its history entry together.

    only_if_missing inserts atomically or not at all, so concurrent startups seed once.
    """
    verb = 'INSERT OR IGNORE' if only_if_missing else 'INSERT OR REPLACE'
    if db.execute(f'{verb} INTO catalog_items VALUES (?,?,?,?,?)', (kind, identity, revision, canonical(payload), now)).rowcount:
        db.execute('INSERT INTO catalog_history(kind,id,revision,payload,updated_at) VALUES (?,?,?,?,?)',
                   (kind, identity, revision, canonical(payload), now))


class Catalog:
    def __init__(self, store):
        self.store = store
        with store.connect() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS catalog_items (
                  kind TEXT NOT NULL, id TEXT NOT NULL, revision INTEGER NOT NULL,
                  payload TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY(kind, id));
                CREATE TABLE IF NOT EXISTS catalog_history (
                  cursor INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, id TEXT NOT NULL,
                  revision INTEGER NOT NULL, payload TEXT NOT NULL, updated_at TEXT NOT NULL);
            ''')
            now = datetime.now(timezone.utc).isoformat()
            # Insert built-ins only when missing; never overwrite a stored edit.
            for kind, identity, payload in _seeds():
                _write(db, kind, identity, 1, payload, now, only_if_missing=True)
            self._backfill_purchase_fields(db, now)

    @staticmethod
    def _backfill_purchase_fields(db, now):
        """Give already-seeded built-in foods the purchase unit/staple flag they predate, and only that.

        Databases seeded before these fields existed hold the old built-in at revision 1, and
        built-ins are never reinserted, so the fields would never arrive. Only a food still
        identical to its old built-in is updated, as a normal revisioned edit; any food the
        owner has touched keeps their version untouched.
        """
        for identity, raw in catalog_seed.FOODS.items():
            if not set(raw.get('purchase', {})) & BACKFILLED:
                continue
            row = db.execute("SELECT revision, payload FROM catalog_items WHERE kind='food' AND id=?", (identity,)).fetchone()
            if not row:
                continue
            old = {**raw, 'purchase': {k: v for k, v in raw['purchase'].items() if k not in BACKFILLED}}
            # Older servers stored built-ins as written; newer ones store them validated.
            if json.loads(row['payload']) in (old, validate_food(old)):
                _write(db, 'food', identity, row['revision'] + 1, validate_food(raw), now)

    def snapshot(self):
        with self.store.connect() as db:
            rows = db.execute('SELECT * FROM catalog_items ORDER BY kind, id').fetchall()
        out = {'foods': {}, 'templates': {}}
        for r in rows:
            out['foods' if r['kind'] == 'food' else 'templates'][r['id']] = {**json.loads(r['payload']), 'revision': r['revision']}
        return out

    def upsert(self, kind, identity, payload, expected_revision):
        if kind not in KINDS:
            raise DomainError('invalid_catalog_kind')
        if not isinstance(identity, str) or not IDENTIFIER.match(identity):
            raise DomainError('invalid_catalog_id', pattern=IDENTIFIER.pattern)
        if type(expected_revision) is not int or expected_revision < 0:
            raise DomainError('invalid_revision')
        with self.store.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            if kind == 'food':
                clean = validate_food(payload)
            else:
                foods = {r['id'] for r in db.execute("SELECT id FROM catalog_items WHERE kind='food'")}
                clean = validate_template(payload, foods)
            row = db.execute('SELECT revision, payload FROM catalog_items WHERE kind=? AND id=?', (kind, identity)).fetchone()
            current = row['revision'] if row else 0
            if expected_revision != current:
                raise DomainError('revision_conflict', 409, current={'id': identity, 'revision': current,
                                                                     'payload': json.loads(row['payload']) if row else None})
            if row and json.loads(row['payload']) == clean:
                return {'kind': kind, 'id': identity, 'revision': current, 'payload': clean, 'changed': False}
            _write(db, kind, identity, current + 1, clean, datetime.now(timezone.utc).isoformat())
            return {'kind': kind, 'id': identity, 'revision': current + 1, 'payload': clean, 'changed': True}
