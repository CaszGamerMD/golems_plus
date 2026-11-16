"""Construction observations: waits are not completed or failed builds."""
import argparse
import json
import math
from collections import Counter
from pathlib import Path

MARKER='[NNGOLEMS_PLACEMENT] '
FEATURES=['dx_5','dy_5','dz_5','grounded','flight_active','existing_air',
          'existing_fluid','support_air','support_sturdy','desired_can_survive',
          'multiblock','waterlog','bubble','rail','interaction','rail_jig',
          'rail_repair','partner_repair','dependency_repair']
CATEGORIES={'DEPENDENCY_REPAIR','WATERLOG','BUBBLE','RAIL','MULTIBLOCK','INTERACTION','WATER','ORDINARY'}
OUTCOMES={'COMPLETED','REPAIRED_DEPENDENCY','WAIT_INTERACTION','WAIT_CHUNK',
          'WAIT_PARTNER','WAIT_CLEARANCE','WAIT_IGNITION','WAIT_PROTECTED',
          'WAIT_MATERIAL','WAIT_RAIL_SUPPORT','WAIT_RAIL_JIG','WORLD_REJECTED',
          'WAIT_PARTNER_STATE','WAIT_BUBBLE'}


def read_placements(paths):
    seen={}
    for path in paths:
        for line in Path(path).read_text().splitlines():
            if MARKER not in line:
                continue
            r=json.loads(line.split(MARKER,1)[1])
            x=r['features']
            if (r['schema']!=1 or r['category'] not in CATEGORIES
                or r['outcome'] not in OUTCOMES or len(x)!=len(FEATURES)
                or any(not isinstance(v,(int,float)) or not math.isfinite(v) or abs(v)>1 for v in x)
                or any(v not in (0,1) for v in x[3:])
                or not isinstance(r['stateMatches'],bool)
                or r['stateMatches']!=(r['desired']==r['actual'])):
                raise ValueError('Invalid construction observation')
            key=(r['run'],r['id'])
            if key in seen and seen[key]!=r:
                raise ValueError('Conflicting construction observation')
            seen[key]=r
    return list(seen.values())


def summary(rows):
    return dict(observations=len(rows),outcomes=dict(Counter(r['outcome'] for r in rows)),
                exact_state_matches=sum(r['stateMatches'] for r in rows))


def report(rows):
    return dict(schema=1,features=FEATURES,overall=summary(rows),
                by_category={c:summary([r for r in rows if r['category']==c])
                             for c in sorted({r['category'] for r in rows})},
                by_run={run:summary([r for r in rows if r['run']==run])
                        for run in sorted({r['run'] for r in rows})},
                warning='Near-work decisions, not distinct blocks. Completion is controller-reported; exact equality is diagnostic. No pre-reach failures or counterfactual outcomes.')


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('logs',nargs='+')
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    rows=read_placements(a.logs)
    if not rows or len({r['run'] for r in rows})<3:
        raise ValueError('Need three independent construction runs')
    result=report(rows)
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps(result,indent=2))
    print(json.dumps(result,indent=2))


if __name__=='__main__':
    main()
