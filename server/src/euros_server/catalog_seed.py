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
# purchase.unit = how the item is actually bought (one egg, one banana, one bag/can/tub of a
# stated size) and the bought grams it contributes, so grocery lists count packages instead of
# reporting bare grams. Sizes are typical US grocery packs, not the owner's verified labels;
# foods sold loose by weight (meat, produce weighed at the counter) stay unit-less on purpose.
# purchase.staple = shelf-stable pantry goods bought occasionally (oil, rice, oats...): grocery
# lists name them under check_stock instead of computing an amount to buy.
FOODS = {
    'chicken': _food('cooked chicken breast', 165, 31, 0, 3.6, 'protein', batch_cook=True, purchase=dict(form='raw boneless chicken breast', cooked_yield=.75)),
    'salmon': _food('cooked salmon', 206, 22, 0, 12, 'protein', batch_cook=True, purchase=dict(form='raw salmon fillet', cooked_yield=.8)),
    'ribs': _food('cooked pork rib meat', 288, 26, 0, 20, 'protein'),
    'rice': _food('cooked rice', 130, 2.7, 28.2, .3, 'carb', batch_cook=True, purchase=dict(staple=True, form='dry rice', cooked_yield=2.8, unit=dict(name='1 kg bag of dry rice', grams=1000, plural='1 kg bags of dry rice'))),
    'potato': _food('cooked sweet potato', 90, 2, 20.7, .2, 'carb', batch_cook=True, purchase=dict(form='raw sweet potato', cooked_yield=.9)),
    'vegetables': _food('vegetables', 35, 2, 6, .3, 'fixed', batch_cook=True, purchase=dict(form='raw vegetables', cooked_yield=.9)),
    'oats': _food('dry oats', 389, 16.9, 66.3, 6.9, 'carb', basis='dry', purchase=dict(staple=True, unit=dict(name='18 oz canister of oats', grams=510, plural='18 oz canisters of oats'))),
    'yogurt': _food('plain Greek yogurt', 73, 10, 4, 2, 'protein', basis='as_sold', purchase=dict(unit=dict(name='32 oz tub of Greek yogurt', grams=907, plural='32 oz tubs of Greek yogurt'))),
    'milk': _food('milk', 61, 3.2, 4.8, 3.3, 'fixed', basis='as_sold', purchase=dict(unit=dict(name='half gallon of milk', grams=1950, plural='half gallons of milk'))),
    'whey': _food('whey protein', 400, 80, 10, 6.7, 'protein', basis='as_sold', purchase=dict(staple=True, unit=dict(name='2 lb tub of whey protein', grams=907, plural='2 lb tubs of whey protein'))),
    'banana': _food('banana, peeled', 89, 1.1, 22.8, .3, 'fixed', basis='as_sold', purchase=dict(unit=dict(name='banana', grams=118))),
    'berries': _food('berries', 50, .7, 12, .3, 'fixed', basis='as_sold', purchase=dict(unit=dict(name='6 oz container of berries', grams=170, plural='6 oz containers of berries'))),
    'bread': _food('multigrain bread', 250, 10, 45, 4, 'carb', basis='as_sold', purchase=dict(unit=dict(name='loaf of multigrain bread', grams=680, plural='loaves of multigrain bread'))),
    'eggs': _food('eggs, edible portion', 143, 12.6, .7, 9.5, 'protein', basis='raw', step_g=50, purchase=dict(unit=dict(name='egg', grams=50, pack=dict(name='dozen', plural='dozen', count=12)))),
    'hummus': _food('hummus', 240, 7, 20, 15, 'fat', basis='as_sold', purchase=dict(unit=dict(name='10 oz tub of hummus', grams=283, plural='10 oz tubs of hummus'))),
    'oil': _food('olive oil', 884, 0, 0, 100, 'fat', basis='as_sold', purchase=dict(staple=True, unit=dict(name='500 ml bottle of olive oil', grams=460, plural='500 ml bottles of olive oil'))),
    # v2 additions: familiar, batch-friendly, no frozen prepared meals or takeout.
    'turkey': _food('cooked 93% lean ground turkey', 200, 27, 0, 10, 'protein', batch_cook=True, purchase=dict(form='raw 93% lean ground turkey', cooked_yield=.75)),
    'beef': _food('cooked 90% lean ground beef', 217, 26, 0, 11.8, 'protein', batch_cook=True, purchase=dict(form='raw 90% lean ground beef', cooked_yield=.75)),
    'shrimp': _food('cooked shrimp', 99, 24, .2, .3, 'protein', purchase=dict(form='raw peeled shrimp', cooked_yield=.85)),
    'tuna': _food('canned tuna in water, drained', 116, 25.5, 0, .8, 'protein', basis='as_sold', purchase=dict(unit=dict(name='5 oz can of tuna', grams=110, plural='5 oz cans of tuna'))),
    'cottage': _food('2% cottage cheese', 84, 11, 4.3, 2.3, 'protein', basis='as_sold', active=True, purchase=dict(unit=dict(name='16 oz tub of cottage cheese', grams=454, plural='16 oz tubs of cottage cheese'))),
    'pasta': _food('cooked pasta', 158, 5.8, 30.9, .9, 'carb', batch_cook=True, purchase=dict(staple=True, form='dry pasta', cooked_yield=2.4, unit=dict(name='1 lb box of dry pasta', grams=454, plural='1 lb boxes of dry pasta'))),
    'quinoa': _food('cooked quinoa', 120, 4.4, 21.3, 1.9, 'carb', batch_cook=True, purchase=dict(staple=True, form='dry quinoa', cooked_yield=2.7, unit=dict(name='1 lb bag of dry quinoa', grams=454, plural='1 lb bags of dry quinoa'))),
    'beans': _food('black beans, canned, drained', 132, 8.9, 23.7, .5, 'carb', basis='as_sold', batch_cook=True, purchase=dict(unit=dict(name='15 oz can of black beans', grams=250, plural='15 oz cans of black beans'))),
    'tortilla': _food('flour tortilla', 312, 8.3, 52, 7.9, 'carb', basis='as_sold', purchase=dict(unit=dict(name='tortilla', grams=70))),
    'honey': _food('honey', 304, .3, 82, 0, 'carb', basis='as_sold', purchase=dict(staple=True, unit=dict(name='12 oz jar of honey', grams=340, plural='12 oz jars of honey'))),
    'apple': _food('apple', 52, .3, 13.8, .2, 'fixed', basis='as_sold', purchase=dict(unit=dict(name='apple', grams=180))),
    'avocado': _food('avocado', 160, 2, 8.5, 14.7, 'fat', basis='as_sold', purchase=dict(unit=dict(name='avocado', grams=150))),
    'peanut_butter': _food('peanut butter', 588, 25, 20, 50, 'fat', basis='as_sold', purchase=dict(staple=True, unit=dict(name='16 oz jar of peanut butter', grams=454, plural='16 oz jars of peanut butter'))),
    'almonds': _food('almonds', 579, 21, 22, 50, 'fat', basis='as_sold', purchase=dict(unit=dict(name='16 oz bag of almonds', grams=454, plural='16 oz bags of almonds'))),
    'cheese': _food('cheddar cheese', 403, 25, 1.3, 33, 'fat', basis='as_sold', purchase=dict(unit=dict(name='8 oz block of cheddar cheese', grams=227, plural='8 oz blocks of cheddar cheese'))),
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
    'cottage_berries': _template('Cottage cheese, berries + oats', ['breakfast'], [('cottage', 200), ('berries', 100), ('oats', 40)], active=True),
    'pb_oats': _template('Peanut butter banana oats', ['breakfast'], [('oats', 60), ('milk', 250), ('peanut_butter', 16), ('banana', 120), ('whey', 25)]),
    'egg_avocado_toast': _template('Eggs + avocado toast', ['breakfast'], [('eggs', 100), ('bread', 80), ('avocado', 50)]),
    'turkey_chili': _template('Turkey chili + rice', MAIN, [('turkey', 150), ('beans', 120), ('vegetables', 150), ('rice', 150)], batch=True),
    'burrito_bowl': _template('Turkey burrito bowl', MAIN, [('turkey', 140), ('rice', 180), ('beans', 80), ('vegetables', 100), ('avocado', 40)], batch=True, active=True),
    'beef_pasta': _template('Beef + tomato pasta', MAIN, [('beef', 130), ('pasta', 200), ('vegetables', 150), ('oil', 5)], batch=True),
    'salmon_quinoa': _template('Salmon + quinoa', ['dinner'], [('salmon', 160), ('quinoa', 180), ('vegetables', 150), ('oil', 5)], batch=True),
    'shrimp_rice': _template('Garlic shrimp + rice', ['dinner'], [('shrimp', 150), ('rice', 200), ('vegetables', 150), ('oil', 10)]),
    'tuna_wrap': _template('Tuna avocado wrap', ['lunch'], [('tuna', 120), ('tortilla', 70), ('avocado', 40), ('vegetables', 80)]),
    'cottage_apple': _template('Cottage cheese + apple', ['recovery-snack'], [('cottage', 200), ('apple', 150)], active=True),
    'apple_almonds': _template('Apple + almonds', ['recovery-snack'], [('apple', 150), ('almonds', 25)]),
    'honey_toast': _template('Banana honey toast', ['pre-training'], [('bread', 60), ('banana', 120), ('honey', 15)]),
}
