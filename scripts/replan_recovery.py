"""Preview rest-day recovery replacements; writes commands, never sends them."""
import copy,json,argparse
from datetime import date,datetime,timedelta
from zoneinfo import ZoneInfo
TITLE='Down Dog gentle stretching · 10–15 min'
INSTRUCTIONS='Open Down Dog and choose 10–15 minutes of gentle stretching. Keep it easy. Mark Done when finished, or Skip if you decide not to do it.'
TEMPLATE=dict(id='gentle-stretching',kind='recovery',title=TITLE,instructions=INSTRUCTIONS,sourceRef='recoveryPreference20261021')
def generate(records,start=date(2026,10,22),end=date(2026,11,15)):
 activities=[r for r in records if r['entity_type']=='activities' and not r['deleted']]
 commands=[]
 def command(r,operation,payload):
  commands.append(dict(command_id='recovery-v2-'+r['entity_id'],entity_type='activities',entity_id=r['entity_id'],expected_revision=r['revision'],operation=operation,payload=payload))
 for r in activities:
  p=r['payload'];d=p.get('scheduled_at','')[:10]
  if not str(start)<=d<=str(end) or p.get('kind') not in ('recovery','rest') or p.get('status')!='pending':continue
  children=[c for c in activities if c['payload'].get('parent_id')==r['entity_id']]
  if any(c['payload'].get('status')!='pending' for c in children):continue
  command(r,'delete',{})
 for offset in range((end-start).days+1):
  day=start+timedelta(days=offset)
  if day.weekday() not in (2,4):continue
  # Never place a rest routine on a date with an authored workout, including skips.
  if any(r['payload'].get('kind') in ('bjj','weights') and r['payload'].get('scheduled_at','')[:10]==str(day) for r in activities):continue
  identity='recovery-stretch-'+str(day)
  if any(r['entity_type']=='activities' and r['entity_id']==identity for r in records):continue
  payload=dict(kind='recovery',title=TITLE,status='pending',scheduled_at=datetime.combine(day,datetime.strptime('20:30','%H:%M').time(),ZoneInfo('America/Chicago')).isoformat(),time_zone='America/Chicago',template_id='gentle-stretching',prescription=copy.deepcopy(TEMPLATE),plan_snapshot={'template':copy.deepcopy(TEMPLATE)},plan_revision='recovery-v2')
  command(dict(entity_id=identity,revision=0),'upsert',payload)
 return commands
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('snapshot');parser.add_argument('output');args=parser.parse_args()
 commands=generate(json.load(open(args.snapshot))['records']);open(args.output,'w').write(json.dumps(commands,indent=2)+'\n');print(len(commands),'proposed commands; no records written')
