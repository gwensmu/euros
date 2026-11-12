"""Grocery list from saved meal plans: read-only aggregation, never a purchase or log.

Only saved (applied) plans count; days without one are reported missing, not filled.
Quantities convert to purchase form through the catalog's cooked_yield. Foods whose
basis needs a conversion the catalog does not have are unresolved, never invented.
Meals from use_up templates eat existing inventory and are excluded from buying.
"""
import math
from datetime import timedelta

from .meal_week import _parse_week
from .nutrition_policy import resolve

# Catalog basis values that are already the bought form when no purchase info exists.
AS_BOUGHT = ('as_sold', 'raw', 'dry')


def grocery_list(store, catalog, week_start, today=None):
    start, week = _parse_week(week_start, None)
    saved = store.meal_plans(str(start), str(start + timedelta(days=6)))['records']
    plans = {r['payload']['date']: r['payload'] for r in saved}
    snapshot = catalog.snapshot()
    foods, templates = snapshot['foods'], snapshot['templates']
    records = store.records('weight_entries')['records']
    planned, use_up, unresolved = {}, [], []
    for d in sorted(plans):
        plan = plans[d]
        maintenance = resolve(records, d, plan.get('nutrition_targets'), today=today)['mode'] == 'maintenance'
        meals = plan.get('maintenance_meals') if maintenance else plan.get('meals', [])
        if maintenance and not meals:
            for m in plan.get('meals', []):
                unresolved.append(dict(date=d, meal_id=m['id'], title=m['title'], foods=m.get('foods', []),
                                       reason='maintenance portions unavailable; baseline quantities not substituted'))
            continue
        for m in meals:
            if templates.get(m.get('template_id'), {}).get('use_up'):
                use_up.append(dict(date=d, meal_id=m['id'], title=m['title']))
                continue
            if not m.get('items'):
                unresolved.append(dict(date=d, meal_id=m['id'], title=m['title'], foods=m.get('foods', []),
                                       reason='no structured ingredient quantities; convert this saved meal before shopping'))
                continue
            for i in m.get('items', []):
                grams, meals = planned.get(i['food_id'], (0, 0))
                planned[i['food_id']] = (grams + i['grams'], meals + 1)
    items = []
    for food_id in sorted(planned):
        grams, meals = planned[food_id]
        food = foods.get(food_id)
        if food is None:
            unresolved.append(dict(food_id=food_id, planned_g=round(grams, 1), reason='not in catalog'))
            continue
        purchase, yield_ = food.get('purchase', {}), food.get('purchase', {}).get('cooked_yield')
        if yield_:
            form, buy = purchase.get('form', food['name']), grams / yield_
        elif food.get('basis') in AS_BOUGHT:
            form, buy = purchase.get('form', food['name']), grams
        else:
            unresolved.append(dict(food_id=food_id, name=food['name'], planned_g=round(grams, 1),
                                   reason=f"basis {food.get('basis')} with no purchase cooked_yield"))
            continue
        step = max(1, int(food.get('step_g') or 1))
        items.append(dict(food_id=food_id, name=food['name'], buy_form=form,
                          buy_g=math.ceil(buy / step) * step, planned_g=round(grams, 1), meals=meals))
    return dict(week_start=week_start,
                planned_dates=sorted(plans),
                missing_dates=[str(d) for d in week if str(d) not in plans],
                items=items, unresolved=unresolved, use_up_meals=use_up,
                notes='Saved plans only; quantities are purchase-form estimates before any pantry subtraction. '
                      'Nothing is logged, deducted or bought by this tool.')
