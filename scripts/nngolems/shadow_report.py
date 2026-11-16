"""Join shadow recommendations to actual accepted-flight outcomes; never label alternatives."""
import argparse
import json
import math
from collections import Counter
from pathlib import Path
from actions import read_actions, TERMINAL

MARKER='[NNGOLEMS_SHADOW] '
MODEL='[NNGOLEMS_SHADOW_MODEL] '


def report(path):
    lines=Path(path).read_text().splitlines()
    models=[l.split(MODEL,1)[1] for l in lines if MODEL in l]
    if len(models)!=1 or any('[NNGOLEMS_SHADOW_ERROR]' in l for l in lines):
        raise ValueError('Missing model or shadow inference error')
    actual={(r['run'],r['id']):r for r in read_actions([path])}
    rows={}
    for line in lines:
        if MARKER not in line: continue
        r=json.loads(line.split(MARKER,1)[1])
        key=(r['run'],r['id'])
        c=r['candidates']
        j=r['recommended']
        if (r['schema']!=1 or not c or not 0<=j<len(c) or r['wouldChange']!=(j!=0)
                or any(not math.isfinite(v['score']) or not 0<=v['score']<=1
                       or not math.isfinite(v['length']) or v['length']<0 for v in c)):
            raise ValueError('Invalid shadow row')
        if j and (c[j]['score']-c[0]['score']<.10-1e-9 or c[j]['length']>c[0]['length']*1.25+.02+1e-9):
            raise ValueError('Recommendation exceeded guard')
        if key in rows and rows[key]!=r: raise ValueError('Duplicate shadow action')
        rows[key]=r
    if not rows: raise ValueError('No shadow observations')
    if set(actual)-set(rows): raise ValueError('Unscored recorded action')
    terminal=[(r,actual[k]) for k,r in rows.items() if k in actual and actual[k]['outcome'] in TERMINAL]
    changes=[(r,a) for r,a in terminal if r['wouldChange']]
    return dict(model=models[0],scored=len(rows),multi_candidate=sum(len(r['candidates'])>1 for r in rows.values()),
                would_change=sum(r['wouldChange'] for r in rows.values()),
                terminal=len(terminal),unobserved_or_censored=len(rows)-len(terminal),
                actual_outcomes=dict(Counter(a['outcome'] for r,a in terminal)),
                actual_outcomes_when_change=dict(Counter(a['outcome'] for r,a in changes)),
                accepted_low_score=sum(r['candidates'][0]['score']<r['threshold'] for r,a in terminal),
                warning='Only the conventional accepted route executed; alternate outcomes are unknown.')


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('root',type=Path)
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    result={p.parent.name:report(p) for p in sorted(a.root.glob('*/server.log'))}
    if len(result)!=6: raise ValueError('Expected six shadow trials')
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps(result,indent=2))
    print(json.dumps(result,indent=2))
if __name__=='__main__': main()
