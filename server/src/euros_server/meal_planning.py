"""Read-only meal planning context shared by agents. Values are planning estimates."""
from .nutrition_policy import TIERS

# Per 100 g; generic estimates, not verified brand labels or historical food records.
FOODS = {
 'chicken': ('cooked chicken breast',165,31,0,3.6),
 'salmon': ('cooked salmon',206,22,0,12),
 'ribs': ('cooked pork rib meat',288,26,0,20),
 'rice': ('cooked rice',130,2.7,28.2,.3),
 'potato': ('cooked sweet potato',90,2,20.7,.2),
 'vegetables': ('vegetables',35,2,6,.3),
 'oats': ('dry oats',389,16.9,66.3,6.9),
 'yogurt': ('plain Greek yogurt',73,10,4,2),
 'milk': ('milk',61,3.2,4.8,3.3),
 'whey': ('whey protein',400,80,10,6.7),
 'banana': ('banana, peeled',89,1.1,22.8,.3),
 'berries': ('berries',50,.7,12,.3),
 'bread': ('multigrain bread',250,10,45,4),
 'eggs': ('eggs, edible portion',143,12.6,.7,9.5),
 'hummus': ('hummus',240,7,20,15),
 'oil': ('olive oil',884,0,0,100),
}
TEMPLATES = {
 'oats':dict(title='Oats, yogurt + banana',items=[('oats',60),('yogurt',200),('banana',120)]),
 'eggs':dict(title='Eggs + toast',items=[('eggs',150),('bread',80),('berries',100)]),
 'yogurt':dict(title='Yogurt, oats + berries',items=[('yogurt',250),('oats',60),('berries',100)]),
 'chicken_rice':dict(title='Chicken rice bowl',items=[('chicken',150),('rice',200),('vegetables',150),('oil',10)]),
 'chicken_potato':dict(title='Chicken + sweet potato',items=[('chicken',150),('potato',250),('vegetables',150),('oil',10)]),
 'salmon_rice':dict(title='Salmon + rice',items=[('salmon',160),('rice',200),('vegetables',150)]),
 'salmon_potato':dict(title='Salmon + sweet potato',items=[('salmon',160),('potato',250),('vegetables',150)]),
 'chicken_sandwich':dict(title='Chicken + hummus sandwich',items=[('chicken',120),('bread',100),('hummus',40),('vegetables',100)]),
 'ribs_use_up':dict(title='Rib meat + sweet potato',items=[('ribs',150),('potato',250),('vegetables',150)],replenish=False),
 'pre_training':dict(title='Pre-training snack',items=[('banana',120),('bread',60)]),
 'snack':dict(title='Yogurt + berries',items=[('yogurt',200),('berries',100)]),
}
GUIDANCE = [
 'Read meal_planning_context first. Read dated plans, planned training and current intake before proposing changes.',
 'Daily calorie tiers are practical guides. Protein about 135 g daily; macro numbers are planning allocations, not exact tests. No food debt or exercise eat-back.',
 'Use the accepted planned training tier. A move, addition or explicit skip prompts a proposed adjustment; elapsed time or absent logs never implies skipping. Preserve the accepted target until revision is authorized.',
 'Maintenance uses seven consecutive daily readings averaging <=155 lb: earliest observed reading per day, one day one vote. Missing days break the window. Add 200 kcal and 50 g carbs to the accepted tier. Later gains do not revert it; corrections/deletions recompute.',
 'Plan small batches of 3–4 portions. Familiar foods include eggs, oats, yogurt, chicken, salmon, rice, sweet potatoes, bread and fruit. No frozen prepared meals or routine takeout; an occasional burrito is fine.',
 'Rib meat is use-up only; do not replenish or schedule indefinitely. Fridge availability is user-reported, not inferred from meal or prep logs. Never claim a meal was prepped yesterday.',
 'Alternatives and replanning happen with the agent, not in the app. Phone shows only current dated meals, times, portions and water reminders.',
 'Use cooked/raw preparation state with gram amounts. Generic template coefficients are estimates. Prefer an actual label when available; keep source/version snapshots. Do not silently rewrite logged nutrients.',
 'Dated plans may include maintenance_meals with the same stable IDs and larger portions. Select these only when nutrition_totals says maintenance; baseline meals remain the loss snapshot.',
 'Use stable meal IDs. Preserve current payload fields and accepted revision; consumption and prep completion are separate. Planning never logs food, deducts pantry or creates purchases.',
 'Before saving, preview totals and explain meaningful gaps or schedule assumptions. User-requested replanning authorizes the stated dates; unrelated or inferred changes remain proposals.',
 'When meals change mid-day, revise only remaining meals and account for logged intake without assuming that the day is fully logged. Do not overwrite the original target to match what was eaten.',
 'Prep activities have kind prep, a date/time, instructions and covered meal IDs. Reminder opens prep details. No inferred consumption or fridge stock from Done.',
]

def context():
 return dict(version='2026-10-22-v1',tiers=TIERS,maintenance_add_kcal=200,maintenance_add_carbs_g=50,guidance=GUIDANCE,
             coefficient_basis='generic planning estimates per 100 g, not verified labels',foods=FOODS,templates=TEMPLATES)

def preview(payload):
 from .domain import validate_meal_plan
 validate_meal_plan(payload)
 totals={k:round(sum(float(m[k]) for m in payload['meals'] if isinstance(m.get(k),(float,int))),1) for k in ('kcal','proteinG','carbohydrateG','fatG')}
 missing={k:sum(not isinstance(m.get(k),(float,int)) for m in payload['meals']) for k in totals}
 return dict(planned_totals=totals,missing=missing,targets=payload['nutrition_targets'],saved=False)
