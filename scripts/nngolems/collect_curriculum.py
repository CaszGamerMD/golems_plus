"""Preserve curriculum observations without discarding courses with no flights."""
import argparse
import hashlib
import json
from pathlib import Path
from train import read_rows, MARKER
from actions import read_actions, MARKER as ACTION_MARKER, summarize
from placements import read_placements, MARKER as PLACEMENT_MARKER, summary

def main():
    p=argparse.ArgumentParser()
    p.add_argument('log',type=Path)
    p.add_argument('output',type=Path)
    p.add_argument('--course',required=True)
    p.add_argument('--commit',required=True)
    a=p.parse_args()
    raw=a.log.read_bytes()
    lines=raw.decode().splitlines()
    def unique(marker):
        found=[line.split(marker,1)[1] for line in lines if marker in line]
        if len(found)!=1 or 'course='+a.course+' ' not in found[0]:
            raise ValueError('Missing or ambiguous '+marker)
        return found[0]
    source=unique('[NN_CURRICULUM_IMPORT] ')
    result=unique('[NN_CURRICULUM_RESULT] ')
    out=a.output
    out.mkdir(parents=True,exist_ok=True)
    manifest=dict(course=a.course,commit=a.commit,source=source,result=result,
                  log_sha256=hashlib.sha256(raw).hexdigest())
    if 'outcome=IMPORT_ONLY' not in result:
        manifest['environment']=unique('[NN_CURRICULUM_ENV] ')
        movement=read_rows([a.log])
        flights=read_actions([a.log])
        placements=read_placements([a.log])
        runs={r['run'] for r in movement+flights+placements}
        if len(runs)>1:
            raise ValueError('Mixed physical observations')
        for name,marker,rows in [('samples',MARKER,movement),('actions',ACTION_MARKER,flights),
                                 ('placements',PLACEMENT_MARKER,placements)]:
            (out/(name+'.log')).write_text(''.join(marker+json.dumps(r)+'\n' for r in rows))
        manifest.update(run=next(iter(runs),None),movement_samples=len(movement),
                        flights=summarize(flights),placements=summary(placements))
    (out/'provenance.json').write_text(json.dumps(manifest,indent=2))
    print(json.dumps(manifest,indent=2))
if __name__=='__main__':
    main()
