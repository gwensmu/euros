"""Balance a day's template portions toward protein/carbohydrate/fat targets.

Protein, carb and fat items scale by role within per-item bounds; fixed items (fruit,
vegetables, milk) keep their template amounts. Hitting the three macros hits calories,
since the accepted tiers are internally consistent. Results are rounded to cookable steps.
"""
ROLE_MACRO = {'protein': 'proteinG', 'carb': 'carbohydrateG', 'fat': 'fatG'}
# Bounds as multiples of the template amount, unless the template item sets min_g/max_g.
ROLE_BOUNDS = {'protein': (.6, 1.6), 'carb': (.4, 2.5), 'fat': (0, 3), 'fixed': (1, 1)}
# Squared error per macro; protein weighs extra because ~135 g is the firm daily target.
WEIGHT = {'proteinG': 6, 'carbohydrateG': 4, 'fatG': 9}
# Calories matter too: when fat cannot reach its target, carbohydrate makes up the energy.
KCAL_WEIGHT = 1
# Gentle pull toward template proportions so portions stay recognisable.
SHAPE_WEIGHT = .3


def target_value(value):
    if isinstance(value, dict):
        return (float(value['min']) + float(value['max'])) / 2
    return float(value)


def _bounds(item, food):
    low, high = ROLE_BOUNDS[food['role']]
    return item.get('min_g', item['base'] * low), item.get('max_g', item['base'] * high)


def _totals(items, foods):
    return {m: sum(foods[i['food_id']]['per100g'][m] * i['grams'] / 100 for i in items) for m in ('kcal', *WEIGHT)}


def _error(items, foods, targets):
    t = _totals(items, foods)
    # Drift from template amounts measured in kcal, so a spoon of oil is not "200% bigger".
    shape = sum(((i['grams'] - i['base']) * foods[i['food_id']]['per100g']['kcal'] / 100) ** 2
                for i in items if foods[i['food_id']]['role'] != 'fixed')
    return (sum((WEIGHT[m] * (t[m] - targets[m])) ** 2 for m in WEIGHT)
            + (KCAL_WEIGHT * (t['kcal'] - targets['kcal'])) ** 2 + SHAPE_WEIGHT * shape)


def balance(meals, foods, targets):
    """Mutates item grams in place. meals: [{'items': [{'food_id', 'grams', min_g?, max_g?}]}]."""
    targets = {m: target_value(targets[m]) for m in ('kcal', *WEIGHT)}
    items = [i for m in meals for i in m['items']]
    for i in items:
        i.setdefault('base', i['grams'])
    by_role = {r: [i for i in items if foods[i['food_id']]['role'] == r] for r in ROLE_MACRO}

    def apply(role, scale):
        for i in by_role[role]:
            low, high = _bounds(i, foods[i['food_id']])
            i['grams'] = min(high, max(low, i['base'] * scale))

    # Continuous solve: Gauss-Seidel over roles, bisection on each monotone role scale.
    scales = {r: 1.0 for r in ROLE_MACRO}
    for _ in range(25):
        for role, macro in ROLE_MACRO.items():
            if not by_role[role]:
                continue
            low, high = 0.0, 6.0
            for _ in range(40):
                mid = (low + high) / 2
                apply(role, mid)
                if _totals(items, foods)[macro] < targets[macro]:
                    low = mid
                else:
                    high = mid
            scales[role] = (low + high) / 2
            apply(role, scales[role])

    # Round to cookable steps, then greedy single-step moves while they reduce the error.
    adjustable = [i for i in items if foods[i['food_id']]['role'] != 'fixed']
    for i in adjustable:
        step = foods[i['food_id']].get('step_g', 5)
        low, high = _bounds(i, foods[i['food_id']])
        i['grams'] = min(max(round(i['grams'] / step) * step, low), high)
    best = _error(items, foods, targets)
    for _ in range(200):
        improved = False
        for i in adjustable:
            step = foods[i['food_id']].get('step_g', 5)
            low, high = _bounds(i, foods[i['food_id']])
            for delta in (step, -step):
                previous = i['grams']
                candidate = previous + delta
                if not low - 1e-9 <= candidate <= high + 1e-9:
                    continue
                i['grams'] = candidate
                error = _error(items, foods, targets)
                if error < best - 1e-6:
                    best, improved = error, True
                    break
                i['grams'] = previous
        if not improved:
            break
    for i in items:
        i['grams'] = round(i['grams'], 1)
        i.pop('base', None)
    for m in meals:
        m['items'] = [i for i in m['items'] if i['grams'] > 0]
    return _totals(items, foods)
