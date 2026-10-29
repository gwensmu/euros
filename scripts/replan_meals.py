"""Generate reviewable revision-checked commands from an explicit canonical snapshot.
No network writes. Actual snapshots and generated personal commands stay outside Git.
"""
import argparse,copy,json
from datetime import date,datetime,timedelta
from pathlib import Path
from zoneinfo import ZoneInfo
from euros_server.meal_planning import FOODS,TEMPLATES,preview
from euros_server.nutrition_policy import TIERS
ZONE=ZoneInfo('America/Chicago')
NAMES=('kcal','proteinG','carbohydrateG','fatG')

def meal(template,identity,clock):
 t=copy.deepcopy(TEMPLATES[template]);return dict(id=identity,title=t['title'],localTime=clock,appliesWhen='every_day',template_id=template,template_version='2026-10-22-v1',items=[dict(food_id=k,grams=g) for k,g in t['items']],sourceRef='generic-planning-estimates-v1')

def calculate(m):
 m.update({n:round(sum(FOODS[i['food_id']][j+1]*i['grams']/100 for i in m['items']),1) for j,n in enumerate(NAMES)})
 m['foods']=[f"{i['grams']:g} g {FOODS[i['food_id']][0]}" for i in m['items']]
 return m

def generate(records,start=date(2026,10,22),end=date(2027,1,13)):
 existing={(r['entity_type'],r['entity_id']):r for r in records}
 activities=[r for r in records if r['entity_type']=='activities' and not r['deleted'] and r['payload'].get('kind') in ('bjj','weights')]
 commands=[]
 for offset in range((end-start).days+1):
  day=start+timedelta(days=offset);key='meal-plan-'+str(day)
  old=existing.get(('meal_plans',key))
  if old and old['deleted']:continue
  actual=[r for r in activities if r['payload']['scheduled_at'][:10]==str(day)]
  sessions=[(r['payload']['kind'],datetime.fromisoformat(r['payload']['scheduled_at']).astimezone(ZONE).strftime('%H:%M')) for r in actual]
  provisional=day>date(2026,11,15)
  if provisional:
   sessions={0:[('bjj','11:00')],1:[('bjj','18:00')],3:[('weights','16:00'),('bjj','20:30')],5:[('bjj','10:00')],6:[('weights','11:00')]}.get(day.weekday(),[])
  tier='bjj' if day==end else 'double' if len(sessions)>=2 else 'bjj' if any(k=='bjj' for k,t in sessions) else 'lifting' if sessions else 'recovery'
  target=TIERS[tier]
  meals=[meal(['oats','eggs','yogurt'][offset%3],'breakfast','08:00'),meal('chicken_rice' if offset%2==0 else 'chicken_sandwich','lunch','12:30'),meal('salmon_potato' if day.weekday() in (2,3) else 'chicken_potato','dinner','18:30'),meal('snack','recovery-snack','20:30')]
  if offset==0:meals[1]=meal('ribs_use_up','lunch','12:30')
  # A salmon batch has two consecutive dinner portions; chicken batches cover lunches.
  if sessions:
   first=min(t for k,t in sessions)
   if first<'13:00':
    meals[0]['localTime']='07:30';meals[1]['localTime']='13:00'
   bjj=next((t for k,t in sessions if k=='bjj'),None)
   if bjj:
    at=datetime.combine(day,datetime.strptime(bjj,'%H:%M').time(),ZONE)
    snack_at=(at-timedelta(minutes=90)).strftime('%H:%M')
    meals.append(meal('pre_training','pre-training',snack_at))
    if bjj>='17:00':
     # Dinner before late classes; no invented training finish or post-session time.
     meals[2]['localTime']='17:30' if bjj>='20:00' else '20:30'
     meals[3]['localTime']='21:00' if bjj<'20:00' else '15:00'
  # Keep useful round portions; target calories are a guide, not a precision exercise.
  for m in meals:calculate(m)
  total=sum(m['kcal'] for m in meals)
  # Adjust starch portions in main meals, rather than padding a plan with duplicate snacks.
  starch=[i for m in meals for i in m['items'] if i['food_id'] in ('rice','potato','oats','bread')]
  diff=target['kcal']-total
  for i in starch:
   share=diff/len(starch);i['grams']=max(30,round((i['grams']+share/FOODS[i['food_id']][1]*100)/5)*5)
  for m in meals:calculate(m)
  protein=sum(m['proteinG'] for m in meals)
  if protein<130:
   m=next(m for m in meals if m['id']=='recovery-snack');m['items'].append(dict(food_id='whey',grams=round((135-protein)/.8/5)*5));calculate(m)
  # Stable IDs retain existing meal links; new optional metadata is provenance, not intake.
  payload=copy.deepcopy(old['payload']) if old else {}
  payload.update(date=str(day),reference_day=tier,nutrition_targets=target,meals=sorted(meals,key=lambda m:m['localTime']),policy_version='2026-10-22-v1',training_basis='provisional-weekly-pattern' if provisional else 'saved-schedule',training_snapshot=[dict(id=r['entity_id'],scheduled_at=r['payload']['scheduled_at'],kind=r['payload']['kind'],status=r['payload']['status']) for r in actual])
  payload['planned_totals']=preview(payload)['planned_totals']
  maintenance_meals=copy.deepcopy(payload['meals'])
  extra=[i for m in maintenance_meals for i in m['items'] if i['food_id'] in ('rice','potato','oats','bread')]
  for i in extra:i['grams']+=round((200/len(extra)/FOODS[i['food_id']][1]*100)/5)*5
  payload['maintenance_meals']=[calculate(m) for m in maintenance_meals]
  payload['maintenance_planned_totals']={n:round(sum(m[n] for m in maintenance_meals),1) for n in NAMES}
  commands.append(dict(command_id='nutrition-v2-'+str(day),entity_type='meal_plans',entity_id=key,expected_revision=old['revision'] if old else 0,operation='upsert',payload=payload))
  if (day.weekday() in (2,6) or day==start+timedelta(days=1)) and day<end:
   identity='prep-v2-'+str(day)
   if ('activities',identity) in existing:continue
   salmon=day.weekday()==2
   instructions=('Cook 2 salmon portions for dinner today and tomorrow. ' if salmon else '')+'Cook chicken breasts, rice or sweet potatoes and vegetables for 3–4 meal portions. Portion using the dated meal plan; refrigerate promptly.'
   commands.append(dict(command_id=identity,entity_type='activities',entity_id=identity,expected_revision=0,operation='upsert',payload=dict(kind='prep',title='Prep for the next few meals',status='pending',scheduled_at=datetime.combine(day,datetime.strptime('18:00' if salmon or day==start+timedelta(days=1) else '16:30','%H:%M').time(),ZONE).isoformat(),time_zone=str(ZONE),prescription=dict(instructions=instructions),covered_dates=[str(day+timedelta(days=i)) for i in range(3)],plan_revision='nutrition-v2')))
 plans={c['payload']['date']:c['payload'] for c in commands if c['entity_type']=='meal_plans'}
 for c in commands:
  if c['entity_type']!='activities':continue
  p=c['payload'];at=datetime.fromisoformat(p['scheduled_at']);covered=[]
  for d in p['covered_dates']:
   for m in plans.get(d,{}).get('meals',[]):
    if not m['template_id'].startswith(('chicken','salmon')):continue
    if d==str(at.date()) and m['localTime']<at.strftime('%H:%M'):continue
    covered.append(dict(date=d,meal_id=m['id'],title=m['title'],localTime=m['localTime']))
  p['covered_meals']=covered[:4]
 return commands

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('snapshot',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
 commands=generate(json.loads(a.snapshot.read_text())['records']);a.output.write_text(json.dumps(commands,indent=2)+'\n')
 print(f'{len(commands)} proposed commands; no records written')
