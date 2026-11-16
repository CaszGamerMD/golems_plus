"""Speed benchmark: compare complete, exact physical builds in game ticks.
Wall runtime is diagnostic only. A stalled/mismatched build never gets a speed score.
"""
import argparse
import json
import statistics
from pathlib import Path

MARKER='[NN_SPEED_RESULT] '
COURSES={'home':620,'dragon':1032}
TRIALS={1,2,3}


def parse(path):
    lines=Path(path).read_text().splitlines()
    results=[line.split(MARKER,1)[1] for line in lines if MARKER in line]
    if len(results)!=1:
        raise ValueError('Expected one speed result: '+str(path))
    row=dict(token.split('=',1) for token in results[0].split())
    for key in ('correct','total','elapsedTicks','elapsedMs','unsupported','crewAlive','crewUnclaimed'):
        row[key]=int(row[key])
    if (row['course'] not in COURSES or row['total']!=COURSES[row['course']]
        or not 0<=row['correct']<=row['total']
        or row['elapsedTicks']<=0 or row['elapsedMs']<0
        or row['outcome'] not in {'COMPLETE','MISMATCH','NO_WORK_PROGRESS','TIME_LIMIT','TICK_LIMIT'}
        or not 0<=row['crewAlive']<=4 or not 0<=row['crewUnclaimed']<=4):
        raise ValueError('Invalid speed result')
    if row['outcome']=='COMPLETE' and (row['correct']!=row['total'] or row['unsupported']):
        raise ValueError('False completion')
    row['game_seconds']=row['elapsedTicks']/20
    row['wall_seconds']=row['elapsedMs']/1000
    row['complete']=row['outcome']=='COMPLETE' and row['crewAlive']==4
    row['blocks_per_game_minute']=row['correct']*1200/row['elapsedTicks'] if row['complete'] else None
    return row


def summarize(rows):
    complete=[r for r in rows if r['complete']]
    ticks=[r['elapsedTicks'] for r in complete]
    result=dict(trials=len(rows),completed=len(complete),
                completion_rate=len(complete)/len(rows),runs=rows,
                median_ticks=statistics.median(ticks) if ticks else None,
                min_ticks=min(ticks) if ticks else None,max_ticks=max(ticks) if ticks else None,
                spread_ticks=max(ticks)-min(ticks) if ticks else None,
                median_blocks_per_game_minute=statistics.median(r['blocks_per_game_minute'] for r in complete) if ticks else None,
                eligible_for_speed_comparison=len(complete)==len(rows))
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('root',type=Path)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--commit',required=True)
    a=p.parse_args()
    report=dict(commit=a.commit,course_limit=2000,crew=4,scaffolding=False,
                warning='Accelerated server wall time is not gameplay time. Only exact complete runs with all workers alive qualify; return-to-depot is not timed or verified. Baseline only, no neural policy.')
    report['courses']={}
    for course in COURSES:
        rows=[]
        for trial in sorted(TRIALS):
            path=a.root/f'nn-speed-{course}-{trial}'/'server.log'
            row=parse(path)
            if row['course']!=course:
                raise ValueError('Course mismatch')
            row['trial']=trial
            rows.append(row)
        report['courses'][course]=summarize(rows)
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps(report,indent=2))
    print(json.dumps(report,indent=2))


if __name__=='__main__':
    main()
