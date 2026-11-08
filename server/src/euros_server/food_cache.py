"""Persistent exact-item nutrition estimates; never consumption records."""
import asyncio
import hashlib
import json
from decimal import Decimal

from .domain import DomainError, NUTRIENTS, canonical
from .llm import exact_object


class FoodCache:
    def __init__(self, store, models):
        self.store, self.models = store, models
        # Bounded lock storage coalesces identical misses within the app process.
        self.locks = [asyncio.Lock() for _ in range(128)]
        self.slots = asyncio.Semaphore(2)
        with store.connect() as db:
            db.execute('CREATE TABLE IF NOT EXISTS food_item_cache '
                       '(key TEXT PRIMARY KEY, input TEXT NOT NULL, result TEXT NOT NULL)')

    async def item(self, text):
        key = hashlib.sha256(text.encode()).hexdigest()
        async with self.locks[int(key[:8], 16) % len(self.locks)]:
            with self.store.connect() as db:
                row = db.execute('SELECT result FROM food_item_cache WHERE key=? AND input=?', (key, text)).fetchone()
            if row:
                return json.loads(row['result'])
            async with self.slots:
                result = await self.models.food({'text': text})
            if result['clarification'] is None and all(v is not None for v in result['nutrients'].values()):
                with self.store.connect() as db:
                    db.execute('INSERT OR REPLACE INTO food_item_cache VALUES (?,?,?)', (key, text, canonical(result)))
            return result

    async def food(self, body):
        exact_object(body, {'text'}, 'invalid_food_request', 422)
        text = body['text']
        if not isinstance(text, str) or not text.strip() or len(text) > 8000:
            raise DomainError('invalid_food_text')
        # Legacy Android Details supplies a total weight for the WHOLE entry. Keep
        # that context intact rather than attaching it to just the last ingredient.
        parts = [text.strip()] if '\nTotal weight of this food entry:' in text else [p.strip() for p in text.split(',') if p.strip()]
        if not parts or len(parts) > 30:
            raise DomainError('invalid_food_text')
        # Wait for all results: successful siblings persist even if one item fails.
        results = await asyncio.gather(*(self.item(part) for part in parts), return_exceptions=True)
        for result in results:
            if isinstance(result, BaseException):
                raise result
        if len(results) == 1:
            return {**results[0], 'raw_text': text}
        unresolved = [r for r in results if r['clarification'] is not None or any(v is None for v in r['nutrients'].values())]
        if unresolved:
            clarification = '; '.join(r['clarification'] or 'Please clarify this food.' for r in unresolved)[:500]
            return {'raw_text': text, 'nutrients': dict.fromkeys(NUTRIENTS), 'quantity_g': None,
                    'clarification': clarification, 'interpretation': results[0]['interpretation']}
        def total(values):
            return None if any(v is None for v in values) else format(sum((Decimal(v) for v in values), Decimal(0)), 'f')
        return {'raw_text': text,
                'nutrients': {key: total([r['nutrients'][key] for r in results]) for key in NUTRIENTS},
                'quantity_g': total([r['quantity_g'] for r in results]), 'clarification': None,
                'interpretation': {'provider': 'food-item-cache', 'model': 'mixed', 'prompt_version': 'exact-items-v1',
                                   'components': [{'text': part, 'interpretation': r['interpretation']} for part, r in zip(parts, results)]}}
