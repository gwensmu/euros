"""Built-in planning catalog, inserted only when an ID is missing; stored edits always win.

Per-100 g values are generic planning estimates, not verified brand labels. Roles drive
portion balancing: protein/carb/fat items scale toward the day's macro targets; fixed items
(fruit, vegetables, milk) keep their template amount. Entries can be switched off
through the agent; inactive entries are not drafted (explicit swaps excepted).
"""

GENERIC = 'generic-planning-estimates-v1'
SUGGESTED = 'generic-planning-estimates-v2'
_V1 = {'cooked chicken breast', 'cooked salmon', 'cooked pork rib meat', 'cooked rice', 'cooked sweet potato', 'vegetables', 'dry oats',
       'plain Greek yogurt', 'milk', 'whey protein', 'banana, peeled', 'berries', 'multigrain bread', 'eggs, edible portion', 'hummus', 'olive oil'}


def _food(name, kcal, p, c, f, role, basis='cooked', step_g=5, batch_cook=False, purchase=None, active=True):
    return dict(name=name, per100g=dict(kcal=kcal, proteinG=p, carbohydrateG=c, fatG=f), role=role, basis=basis,
                step_g=step_g, batch_cook=batch_cook, purchase=purchase or {}, source=GENERIC if name in _V1 else SUGGESTED,
                active=active)


# cooked_yield = cooked grams per raw/dry gram, for later grocery conversion; estimates.
FOODS = {
    'chicken': _food('cooked chicken breast', 165, 31, 0, 3.6, 'protein', batch_cook=True, purchase=dict(form='raw boneless chicken breast', cooked_yield=.75)),
    'salmon': _food('cooked salmon', 206, 22, 0, 12, 'protein', batch_cook=True, purchase=dict(form='raw salmon fillet', cooked_yield=.8)),
    'ribs': _food('cooked pork rib meat', 288, 26, 0, 20, 'protein'),
    'rice': _food('cooked rice', 130, 2.7, 28.2, .3, 'carb', batch_cook=True, purchase=dict(form='dry rice', cooked_yield=2.8)),
    'potato': _food('cooked sweet potato', 90, 2, 20.7, .2, 'carb', batch_cook=True, purchase=dict(form='raw sweet potato', cooked_yield=.9)),
    'vegetables': _food('vegetables', 35, 2, 6, .3, 'fixed', batch_cook=True, purchase=dict(form='raw vegetables', cooked_yield=.9)),
    'oats': _food('dry oats', 389, 16.9, 66.3, 6.9, 'carb', basis='dry'),
    'yogurt': _food('plain Greek yogurt', 73, 10, 4, 2, 'protein', basis='as_sold'),
    'milk': _food('milk', 61, 3.2, 4.8, 3.3, 'fixed', basis='as_sold'),
    'whey': _food('whey protein', 400, 80, 10, 6.7, 'protein', basis='as_sold'),
    'banana': _food('banana, peeled', 89, 1.1, 22.8, .3, 'fixed', basis='as_sold'),
    'berries': _food('berries', 50, .7, 12, .3, 'fixed', basis='as_sold'),
    'bread': _food('multigrain bread', 250, 10, 45, 4, 'carb', basis='as_sold'),
    'eggs': _food('eggs, edible portion', 143, 12.6, .7, 9.5, 'protein', basis='raw', step_g=50),
    'hummus': _food('hummus', 240, 7, 20, 15, 'fat', basis='as_sold'),
    'oil': _food('olive oil', 884, 0, 0, 100, 'fat', basis='as_sold'),
    # v2 additions: familiar, batch-friendly, no frozen prepared meals or takeout.
    'turkey': _food('cooked 93% lean ground turkey', 200, 27, 0, 10, 'protein', batch_cook=True, purchase=dict(form='raw 93% lean ground turkey', cooked_yield=.75)),
    'beef': _food('cooked 90% lean ground beef', 217, 26, 0, 11.8, 'protein', batch_cook=True, purchase=dict(form='raw 90% lean ground beef', cooked_yield=.75)),
    'shrimp': _food('cooked shrimp', 99, 24, .2, .3, 'protein', purchase=dict(form='raw peeled shrimp', cooked_yield=.85)),
    'tuna': _food('canned tuna in water, drained', 116, 25.5, 0, .8, 'protein', basis='as_sold'),
    'cottage': _food('2% cottage cheese', 84, 11, 4.3, 2.3, 'protein', basis='as_sold'),
    'pasta': _food('cooked pasta', 158, 5.8, 30.9, .9, 'carb', batch_cook=True, purchase=dict(form='dry pasta', cooked_yield=2.4)),
    'quinoa': _food('cooked quinoa', 120, 4.4, 21.3, 1.9, 'carb', batch_cook=True, purchase=dict(form='dry quinoa', cooked_yield=2.7)),
    'beans': _food('black beans, canned, drained', 132, 8.9, 23.7, .5, 'carb', basis='as_sold', batch_cook=True),
    'tortilla': _food('flour tortilla', 312, 8.3, 52, 7.9, 'carb', basis='as_sold'),
    'honey': _food('honey', 304, .3, 82, 0, 'carb', basis='as_sold'),
    'apple': _food('apple', 52, .3, 13.8, .2, 'fixed', basis='as_sold'),
    'avocado': _food('avocado', 160, 2, 8.5, 14.7, 'fat', basis='as_sold'),
    'peanut_butter': _food('peanut butter', 588, 25, 20, 50, 'fat', basis='as_sold'),
    'almonds': _food('almonds', 579, 21, 22, 50, 'fat', basis='as_sold'),
    'cheese': _food('cheddar cheese', 403, 25, 1.3, 33, 'fat', basis='as_sold'),
}


def _template(title, slots, items, batch=False, use_up=False, active=True, **bounds):
    return dict(title=title, slots=slots, items=[dict(food_id=k, grams=g, **bounds.get(k, {})) for k, g in items],
                batch=batch, use_up=use_up, active=active)


MAIN = ['lunch', 'dinner']
TEMPLATES = {
    'oats': _template('Oats, yogurt + banana', ['breakfast'], [('oats', 60), ('yogurt', 200), ('banana', 120)]),
    'eggs': _template('Eggs + toast', ['breakfast'], [('eggs', 150), ('bread', 80), ('berries', 100), ('oil', 5)]),
    'yogurt': _template('Yogurt, oats + berries', ['breakfast'], [('yogurt', 250), ('oats', 60), ('berries', 100)]),
    'chicken_rice': _template('Chicken rice bowl', MAIN, [('chicken', 150), ('rice', 200), ('vegetables', 150), ('oil', 10)], batch=True),
    'chicken_potato': _template('Chicken + sweet potato', MAIN, [('chicken', 150), ('potato', 250), ('vegetables', 150), ('oil', 10)], batch=True),
    'salmon_rice': _template('Salmon + rice', MAIN, [('salmon', 160), ('rice', 200), ('vegetables', 150), ('oil', 5)], batch=True),
    'salmon_potato': _template('Salmon + sweet potato', ['dinner'], [('salmon', 160), ('potato', 250), ('vegetables', 150), ('oil', 5)], batch=True),
    'chicken_sandwich': _template('Chicken + hummus sandwich', ['lunch'], [('chicken', 120), ('bread', 100), ('hummus', 40), ('vegetables', 100)], batch=True,
                                  hummus=dict(max_g=60)),
    'ribs_use_up': _template('Rib meat + sweet potato', MAIN, [('ribs', 150), ('potato', 250), ('vegetables', 150)], use_up=True),
    'pre_training': _template('Pre-training snack', ['pre-training'], [('banana', 120), ('bread', 60)]),
    'snack': _template('Yogurt + berries', ['recovery-snack'], [('yogurt', 200), ('berries', 100)]),
    # v2 additions.
    'cottage_berries': _template('Cottage cheese, berries + oats', ['breakfast'], [('cottage', 200), ('berries', 100), ('oats', 40)]),
    'pb_oats': _template('Peanut butter banana oats', ['breakfast'], [('oats', 60), ('milk', 250), ('peanut_butter', 16), ('banana', 120), ('whey', 25)]),
    'egg_avocado_toast': _template('Eggs + avocado toast', ['breakfast'], [('eggs', 100), ('bread', 80), ('avocado', 50)]),
    'turkey_chili': _template('Turkey chili + rice', MAIN, [('turkey', 150), ('beans', 120), ('vegetables', 150), ('rice', 150)], batch=True),
    'burrito_bowl': _template('Turkey burrito bowl', MAIN, [('turkey', 140), ('rice', 180), ('beans', 80), ('vegetables', 100), ('avocado', 40)], batch=True),
    'beef_pasta': _template('Beef + tomato pasta', MAIN, [('beef', 130), ('pasta', 200), ('vegetables', 150), ('oil', 5)], batch=True),
    'salmon_quinoa': _template('Salmon + quinoa', ['dinner'], [('salmon', 160), ('quinoa', 180), ('vegetables', 150), ('oil', 5)], batch=True),
    'shrimp_rice': _template('Garlic shrimp + rice', ['dinner'], [('shrimp', 150), ('rice', 200), ('vegetables', 150), ('oil', 10)]),
    'tuna_wrap': _template('Tuna avocado wrap', ['lunch'], [('tuna', 120), ('tortilla', 70), ('avocado', 40), ('vegetables', 80)]),
    'cottage_apple': _template('Cottage cheese + apple', ['recovery-snack'], [('cottage', 200), ('apple', 150)]),
    'apple_almonds': _template('Apple + almonds', ['recovery-snack'], [('apple', 150), ('almonds', 25)]),
    'honey_toast': _template('Banana honey toast', ['pre-training'], [('bread', 60), ('banana', 120), ('honey', 15)]),
}
