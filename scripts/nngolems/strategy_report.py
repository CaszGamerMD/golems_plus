"""Summarize geometry-only candidate features; not training outcomes."""
import json
import sys
from pathlib import Path
marker='[NN STRATEGY_PREVIEW] '
rows=[json.loads(l.split(marker,1)[1]) for l in Path(sys.argv[1]).read_text().splitlines() if marker in l]
expected={(c,z,a) for c in ('home','dragon','froglights','farm','chibi') for z in range(4) for a in ('Y','X','Z')}
keys={(r['course'],r['zone'],r['result']['axis']) for r in rows}
if keys!=expected or len(rows)!=len(expected):
    raise ValueError('Missing or duplicated strategy candidates')
summary={}
for course in sorted({r['course'] for r in rows}):
    selected=[r for r in rows if r['course']==course]
    by_axis={}
    for axis in ('Y','X','Z'):
        results=[r['result'] for r in selected if r['result']['axis']==axis]
        by_axis[axis]={k:sum(r[k] for r in results) for k in ('steps','coordinateTravel','lowerSupportLate','crossZoneLowerSupport','surroundedCells','batches10','batches32','batches64')}
    summary[course]={'global':by_axis,'zones':[{ 'zone':z,
       'geometry_shortest':min((r['result'] for r in selected if r['zone']==z),key=lambda x:x['coordinateTravel'])['axis'],
       'candidates':[r['result'] for r in selected if r['zone']==z]} for z in range(4)]}
report={'warning':'Coordinate distances and batch counts are idealized estimates, not actual walking, safety, completion, reservations or flight. Axis orders are candidates only.','courses':summary}
Path(sys.argv[2]).write_text(json.dumps(report,indent=2))
print(json.dumps({c:r['global'] for c,r in summary.items()},indent=2))
