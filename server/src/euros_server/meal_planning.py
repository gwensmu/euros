"""Read-only meal planning context shared by agents. Values are planning estimates."""
from .catalog import ROLES, SLOTS
from .meals import meal_totals
from .nutrition_policy import MAINTENANCE_ADD, TIERS

GUIDANCE = [
    'Read meal_planning_context first. Read dated plans, planned training and current intake before proposing changes.',
    'Daily calorie tiers are practical guides. Protein about 120 g daily; macro numbers are planning allocations, not exact tests. No food debt or exercise eat-back.',
    'Use the accepted planned training tier. Training moves automatically update both affected daily budgets from scheduled tiers without changing saved meal portions or consumed food. Additions or explicit skips may prompt proposed meal adjustments; elapsed time or absent logs never implies skipping. Skip alone never lowers a budget.',
    'Maintenance uses seven consecutive daily readings averaging <=155 lb: earliest observed reading per day, one day one vote. Missing days break the window. '
    f"Add {MAINTENANCE_ADD['kcal']} kcal and {MAINTENANCE_ADD['carbohydrateG']} g carbs to the accepted tier. Later gains do not revert it; corrections/deletions recompute.",
    'Foods and templates live in the stored catalog. Edit them with upsert_food/upsert_template using the revision shown; set active false to switch an entry off when the user asks; inactive entries are not drafted. Portions are balanced per day toward protein, carbohydrate and fat targets; template grams are starting amounts.',
    'Plan small batches of 3–4 portions. Familiar foods include eggs, oats, yogurt, chicken, salmon, rice, sweet potatoes, bread and fruit. No frozen prepared meals or routine takeout; an occasional burrito is fine.',
    'Rib meat is use-up only; do not replenish or schedule indefinitely. Fridge availability is user-reported, not inferred from meal or prep logs. Never claim a meal was prepped yesterday.',
    'Alternatives and replanning happen with the agent, not in the app. Phone shows only current dated meals, times, portions and water reminders.',
    'Use cooked/raw preparation state with gram amounts. Generic template coefficients are estimates. Prefer an actual label when available; keep source/version snapshots. Do not silently rewrite logged nutrients.',
    'Each dated plan has one set of meals, balanced to that date\'s targets from nutrition_totals. Maintenance only changes the targets; it is not a separate menu. When targets change (maintenance begins, training moves), redraft the remaining days rather than keeping alternative portions. Older plans may still carry maintenance_meals; ignore them.',
    'Use stable meal IDs. Preserve current payload fields and accepted revision; consumption and prep completion are separate. Planning never logs food, deducts pantry or creates purchases.',
    'Before saving, preview totals and explain meaningful gaps or schedule assumptions. User-requested replanning authorizes the stated dates; unrelated or inferred changes remain proposals.',
    'When meals change mid-day, revise only remaining meals and account for logged intake without assuming that the day is fully logged. Do not overwrite the original target to match what was eaten.',
    'For a week of meals call draft_week, show the result, redraft with swaps/avoid_foods for requested changes, then apply_week after the user accepts. Translate casual requests ("no eggs", "salmon Friday") without asking the user for IDs.',
    'Prep activities have kind prep, a date/time, instructions and covered meal IDs. Reminder opens prep details. No inferred consumption or fridge stock from Done.',
]


def _by_active(items):
    return ({k: v for k, v in items.items() if v['active']}, {k: v for k, v in items.items() if not v['active']})


def context(catalog):
    snapshot = catalog.snapshot()
    foods, suggested_foods = _by_active(snapshot['foods'])
    templates, suggested_templates = _by_active(snapshot['templates'])
    return dict(version='2026-11-09-v2', tiers=TIERS, maintenance_add_kcal=MAINTENANCE_ADD['kcal'],
                maintenance_add_carbs_g=MAINTENANCE_ADD['carbohydrateG'], guidance=GUIDANCE,
                coefficient_basis='per 100 g planning estimates unless a food source names a label',
                meal_slots=list(SLOTS), food_roles=list(ROLES), foods=foods, templates=templates,
                suggested_foods=suggested_foods, suggested_templates=suggested_templates)


def preview(payload):
    from .domain import validate_meal_plan
    validate_meal_plan(payload)
    totals, missing = meal_totals(payload['meals'])
    return dict(planned_totals=totals, missing=missing, targets=payload['nutrition_targets'], saved=False)
