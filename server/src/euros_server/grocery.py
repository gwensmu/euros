"""Grocery list from saved meal plans: read-only aggregation, never a purchase or log.

Only saved (applied) plans count; days without one are reported missing, not filled.
Quantities convert to purchase form through the catalog's cooked_yield, then into whole
store units (eggs, bananas, cans, bags, tubs) through the catalog's purchase.unit, rounding
up and respecting how the unit is packed (eggs by the dozen). Foods whose basis needs a
conversion the catalog does not have are unresolved, never invented; foods with no known
pack size stay a weight-only line rather than a guessed count. Pantry staples
(purchase.staple) get no amount at all: they are listed under check_stock.
Meals from use_up templates eat existing inventory and are excluded from buying.
"""
import math
from datetime import timedelta

from .meals import food_grams, meal_flag, parse_week

# Catalog basis values that are already the bought form when no purchase info exists.
AS_BOUGHT = ('as_sold', 'raw', 'dry')
GRAMS_PER_POUND = 453.59
NOTES = ('Saved plans only; quantities are purchase-form estimates before any pantry subtraction. '
         'buy_text is the shopping line: whole units (eggs, bananas, cans, bags) rounded up from '
         'the catalog pack size where one is known, weight-only for foods sold loose. Pack sizes are '
         "typical, not the local store's; counts round up, so expect leftovers. "
         'check_stock lists pantry staples the week uses (oil, rice, oats...) with no amount: '
         'present them as "check stock, may need replenishment", not as quantities to buy. '
         'Nothing is logged, deducted or bought by this tool.')


def _unresolved_meal(date, meal, reason):
    return dict(date=date, meal_id=meal['id'], title=meal['title'], foods=meal.get('foods', []), reason=reason)


def _planned_grams(plans, templates):
    """Total the week's planned grams per food, with the meals that cannot be totalled."""
    shopped, use_up, unresolved = [], [], []
    for date in sorted(plans):
        for meal in plans[date].get('meals', []):
            if meal_flag(meal, templates, 'use_up'):
                use_up.append(dict(date=date, meal_id=meal['id'], title=meal['title']))
            elif not meal.get('items'):
                unresolved.append(_unresolved_meal(
                    date, meal, 'no structured ingredient quantities; convert this saved meal before shopping'))
            else:
                shopped.append(meal)
    return food_grams(shopped), use_up, unresolved


def _buy_grams(food, planned_g):
    """Grams to buy in the food's purchase form, rounded up to a cookable step.

    Cooked planning grams need the catalog's cooked_yield; a food already in its bought form
    passes through. Anything else cannot be converted and has no honest answer here.
    """
    yield_ = food.get('purchase', {}).get('cooked_yield')
    if not yield_ and food.get('basis') not in AS_BOUGHT:
        return None
    step = max(1, int(food.get('step_g') or 1))
    return math.ceil(planned_g / (yield_ or 1) / step) * step


def _by_count(buy_g, unit):
    """Whole purchase units for the grams needed, honouring how the unit is packed.

    Rounds up twice: no buying two thirds of a can, and no buying seven eggs when the
    carton holds twelve. The pack is reported alongside the count so the line reads the way
    the shelf does -- "12 eggs (1 dozen)" rather than a carton count the user must translate.
    """
    pack = unit.get('pack') or {}
    per_pack = int(pack.get('count') or 1)
    count = max(per_pack, math.ceil(buy_g / unit['grams']))
    count = math.ceil(count / per_pack) * per_pack
    label = unit['name'] if count == 1 else unit['plural']
    out = dict(buy_count=count, buy_unit=label, buy_unit_g=float(unit['grams']),
               buy_unit_total_g=round(count * unit['grams'], 1))
    if per_pack > 1:
        packs = count // per_pack
        out['buy_packs'] = packs
        out['buy_pack'] = pack['name'] if packs == 1 else pack['plural']
        out['buy_pack_count'] = per_pack
    return out


def _buy_text(buy_g, form, counted):
    """The shopping line itself: what to put in the basket, with the grams it covers."""
    if not counted:
        return f'{buy_g:g} g {form} ({buy_g / GRAMS_PER_POUND:.1f} lb, sold by weight)'
    # 'two 1 kg bags' needs the separator; 'two eggs' does not.
    joiner = ' x ' if counted['buy_unit'][:1].isdigit() else ' '
    head = f"{counted['buy_count']}{joiner}{counted['buy_unit']}"
    pack = f"{counted['buy_packs']} {counted['buy_pack']}, " if 'buy_packs' in counted else ''
    return f'{head} ({pack}{buy_g:g} g needed)'


def _item(food_id, food, planned_g, meals):
    """One shopping line, or None when the catalog cannot convert this food."""
    purchase = food.get('purchase', {})
    buy_g = _buy_grams(food, planned_g)
    if buy_g is None:
        return None
    form = purchase.get('form', food['name'])
    counted = _by_count(buy_g, purchase['unit']) if purchase.get('unit', {}).get('grams') else {}
    return dict(food_id=food_id, name=food['name'], buy_form=form, buy_g=buy_g, planned_g=round(planned_g, 1),
                meals=meals, **counted, buy_text=_buy_text(buy_g, form, counted))


def grocery_list(store, catalog, week_start):
    start, week = parse_week(week_start)
    saved = store.meal_plans(str(start), str(start + timedelta(days=6)))['records']
    plans = {r['payload']['date']: r['payload'] for r in saved}
    snapshot = catalog.snapshot()
    foods = snapshot['foods']
    planned, use_up, unresolved = _planned_grams(plans, snapshot['templates'])
    items, check_stock = [], []
    for food_id in sorted(planned):
        planned_g, meals = planned[food_id]['grams'], planned[food_id]['meals']
        food = foods.get(food_id)
        if food is None:
            unresolved.append(dict(food_id=food_id, planned_g=round(planned_g, 1), reason='not in catalog'))
        elif food.get('purchase', {}).get('staple'):
            # Pantry staple: one bag lasts weeks, so name it to check rather than compute an amount.
            check_stock.append(dict(food_id=food_id, name=food['name'], meals=meals,
                                    buy_form=food['purchase'].get('form', food['name'])))
        elif (item := _item(food_id, food, planned_g, meals)) is not None:
            items.append(item)
        else:
            unresolved.append(dict(food_id=food_id, name=food['name'], planned_g=round(planned_g, 1),
                                   reason=f"basis {food.get('basis')} with no purchase cooked_yield"))
    return dict(week_start=week_start,
                planned_dates=sorted(plans),
                missing_dates=[str(d) for d in week if str(d) not in plans],
                items=items, check_stock=check_stock, unresolved=unresolved, use_up_meals=use_up,
                notes=NOTES)
