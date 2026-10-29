#!/usr/bin/env python3
"""Create fictional demo data in a NEW, explicitly selected SQLite database."""
import argparse
from datetime import date, datetime, time, timedelta
import json
import os
from pathlib import Path
from zoneinfo import ZoneInfo


def commands(start):
    zone = ZoneInfo('America/Chicago')
    def stamp(day, hour):
        return datetime.combine(day, time(hour), zone).isoformat()
    def command(kind, identifier, payload):
        return dict(command_id='demo-create-'+identifier, entity_type=kind,
                    entity_id=identifier, expected_revision=0, operation='upsert', payload=payload)
    result=[]
    for offset in range(7):
        day=start+timedelta(days=offset)
        key=day.isoformat()
        kind=['bjj','recovery','weights','rest','bjj','recovery','weight'][offset]
        status=['done','done','skipped','pending','pending','pending','pending'][offset]
        payload=dict(title='Demo '+kind,kind=kind,scheduled_at=stamp(day,18),status=status)
        if status=='done':payload['completed_at']=stamp(day,19)
        if kind=='bjj':payload['plan_snapshot']={'category':'moderate'}
        result.append(command('activities','demo-activity-'+key,payload))
        result.append(command('meal_plans','meal-plan-'+key,dict(date=key,reference_day='Demo',
          nutrition_targets=dict(kcal=2000,proteinG=135,carbohydrateG=230,fatG=60),
          meals=[dict(id='demo-'+name.lower(),title=name,localTime=clock,foods=foods,
                      proteinG=None,carbohydrateG=None,fatG=None,appliesWhen='every_day',sourceRef='synthetic-demo')
                 for name,clock,foods in [('Breakfast','08:00',['2 eggs','80g toast','12–20 oz water']),
                   ('Lunch','12:00',['150g tofu','180g cooked rice','150g vegetables','12–20 oz water']),
                   ('Dinner','19:00',['150g fish','250g potatoes','150g vegetables','12–20 oz water']),
                   ('Snack','21:00',['200g yogurt','100g berries','12–20 oz water'])]])))
        if offset<3:
            result.append(command('food_entries','demo-food-'+key,dict(local_date=key,consumed_at=stamp(day,8),
              raw_text='Demo breakfast: eggs and toast',nutrients=dict(kcal='410',protein_g='21',carbs_g='42',fat_g='17'))))
    for offset,value in [(0,'172.0'),(6,'171.6')]:
        day=start+timedelta(days=offset)
        result.append(command('weight_entries','demo-weight-'+day.isoformat(),
          dict(local_date=day.isoformat(),measured_at=stamp(day,8),weight_lb=value)))
    return result


def seed(path,start):
    from euros_server.domain import Store,validate_payload
    data=commands(start)
    for item in data:validate_payload(item['entity_type'],item['payload'])
    path=Path(path).resolve()
    path.parent.mkdir(parents=True,exist_ok=True)
    # Exclusive creation refuses even an existing empty file; no production reset path.
    descriptor=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    os.close(descriptor)
    store=Store(path)
    with store.connect() as db:
        db.execute("BEGIN IMMEDIATE")
        for item in data:store.command(item,_db=db)
    return len(data)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--db',type=Path,required=True,help='New demo database path; existing files are refused')
    parser.add_argument('--start',type=date.fromisoformat,required=True,help='First demo day, YYYY-MM-DD')
    args=parser.parse_args()
    try:count=seed(args.db,args.start)
    except FileExistsError:parser.exit(2,'Refusing an existing database. Choose a new demo path.\n')
    print(f'Created {count} fictional records in {args.db}')

if __name__=='__main__':main()
