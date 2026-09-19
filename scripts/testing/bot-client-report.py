#!/usr/bin/env python3
"""Compare packets received by two real clients in a bot fixture; never infer aim targets."""
import argparse,collections,json,pathlib,sys
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('run_dir')
parser.add_argument('--matches',type=int,default=1)
parser.add_argument('--map',type=int)
parser.add_argument('--strict',action='store_true',help='Require HP/shot telemetry and clean Java console logs')
args=parser.parse_args()
if args.matches<1:parser.error('--matches must be positive')
root=pathlib.Path(args.run_dir); report={}; streams=[]
for role in 'AB':
    records=[json.loads(line) for line in (root/f'client-{role}.jsonl').read_text().splitlines()]
    actors={}; match=0; packets=[]; counts=collections.Counter(); dead=set(); dead_shots=0
    active_turn=None; turns=[]
    for e in records:
        kind=e['type']; detail=e['detail']
        if kind=='MATCH_START':
            if active_turn:turns.append(active_turn)
            active_turn=None; match+=1; actors={}; dead=set()
        if kind=='ACTOR':
            seat,uid=map(int,detail.split(':')); actors[seat]=uid
        if kind=='TURN':
            if active_turn:turns.append(active_turn)
            seat=int(detail.split(':')[0]);uid=actors.get(seat,-1)
            active_turn={'match':match,'bot':uid,'seat':seat,'shots':0} if uid < -1 else None
        if kind=='SHOT_RECEIVED' and active_turn and int(detail.split(':')[0])==active_turn['seat']:
            active_turn['shots']+=1
        if kind=='MATCH_COMPLETE':
            if active_turn:turns.append(active_turn)
            active_turn=None
        if kind=='HP_RECEIVED':
            seat,hp=map(int,detail.split(':'))
            if hp==0:dead.add(seat)
            else:dead.discard(seat)
        if kind in ('POSITION','ITEM_RECEIVED','SHOT_RECEIVED','HP_RECEIVED'):
            seat,payload=detail.split(':',1); uid=actors.get(int(seat),-1)
            if uid < -1:
                packets.append((match,kind,uid,payload));counts[kind]+=1
                if kind=='SHOT_RECEIVED' and int(seat) in dead:dead_shots+=1
    streams.append(packets)
    summary=root/f'client-{role}.summary'
    done=summary.exists() and 'status=DONE' in summary.read_text()
    maps=[int(e['detail']) for e in records if e['type']=='MATCH_MAP']
    errors=[line for line in (root/f'client-{role}.console.log').read_text().splitlines()
            if 'Exception' in line or 'Error:' in line] if (root/f'client-{role}.console.log').exists() else ['missing console log']
    report[role]={'matches':sum(e['type']=='MATCH_COMPLETE' for e in records),'done':done,
                  'observed_bot_turns':len(turns),'bot_turns_without_shot':sum(t['shots']==0 for t in turns),
                  'maps':maps,'console_errors':errors,
                  'item_counts':dict(collections.Counter(p[3] for p in packets if p[1]=='ITEM_RECEIVED')),
                  'per_match':[{'match':m,'packets':dict(collections.Counter(p[1] for p in packets if p[0]==m))} for m in range(1,match+1)],
                  'bot_packets':dict(counts),'shots_by_dead_bot':dead_shots if any(e['type']=='SHOT_RECEIVED' for e in records) and any(e['type']=='HP_RECEIVED' for e in records) else None,
                  'bot_item_ids':sorted({int(p[3]) for p in packets if p[1]=='ITEM_RECEIVED'})}
report['same_bot_packets']=streams[0]==streams[1]
report['expected_matches']=args.matches
report['passed']=report['same_bot_packets'] and all(report[r]['done'] and report[r]['matches']==args.matches and report[r]['shots_by_dead_bot'] in (0,None) and report[r]['bot_packets'].get('POSITION',0)>0 and report[r]['bot_packets'].get('ITEM_RECEIVED',0)>0
    and (not args.strict or report[r]['shots_by_dead_bot']==0 and not report[r]['console_errors'])
    and (args.map is None or report[r]['maps']==[args.map]*args.matches) for r in 'AB')
report['scope']='Packet consistency only. Corpse targeting/collision is verified separately by Java regression tests; packets do not expose aim target IDs.'
(root/'bot-report.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2));sys.exit(0 if report['passed'] else 1)
