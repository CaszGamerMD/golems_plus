"""Evaluate actual accepted flight actions, not movement-window predictions."""
import argparse
import json
import math
from collections import Counter
from pathlib import Path

MARKER = '[NNGOLEMS_ACTION] '
TERMINAL = {'ARRIVED', 'NO_DISPLACEMENT', 'BLOCKED'}
CENSORED = {'CANCELLED', 'DISABLED', 'REPLACED', 'OBSERVATION_GAP'}
FEATURES = ['dx_2', 'dy_32', 'dz_2', 'route_distance_64', 'legs_3',
            'grounded', 'direct_distance_64']


KNOWLEDGE = ['start_clear_xp','start_clear_xm','start_clear_yp','start_clear_ym',
             'start_clear_zp','start_clear_zm','goal_clear_xp','goal_clear_xm',
             'goal_clear_yp','goal_clear_ym','goal_clear_zp','goal_clear_zm',
             'vertical_first_clear','side_first_clear','over_two_clear',
             'under_two_clear','nearby_builders_4']
BLOCKAGES = {'BLOCK','OTHER_COLLISION','ENVELOPE','UNLOADED_OR_BORDER'}


def read_actions(paths):
    rows, seen = [], {}
    for path in paths:
        for line in Path(path).read_text().splitlines():
            if MARKER not in line:
                continue
            r = json.loads(line.split(MARKER, 1)[1])
            x = r['features']
            if (r['schema'] != 1 or r['action'] != 'FLIGHT'
                    or r['outcome'] not in TERMINAL | CENSORED
                    or len(x) != len(FEATURES)
                    or any(not isinstance(v, (int, float)) or not math.isfinite(v)
                           or abs(v) > 1 for v in x)
                    or r['endTick'] < r['tick']
                    or not math.isfinite(r['remainingDistance'])
                    or r['remainingDistance'] < 0
                    or (r['outcome'] == 'ARRIVED' and r['remainingDistance'] > .021)):
                raise ValueError('Invalid flight action outcome')
            if 'knowledge' in r:
                k = r['knowledge']
                if (len(k) != len(KNOWLEDGE) or any(
                        not isinstance(v,(int,float)) or not math.isfinite(v)
                        or not 0 <= v <= 1 for v in k)
                        or any(v not in (0,1) for v in k[:-1])
                        or r.get('blockage') not in BLOCKAGES | {'NONE'}
                        or (r['outcome']=='BLOCKED') != (r['blockage'] in BLOCKAGES)):
                    raise ValueError('Invalid route knowledge or blockage')
            key = (r['run'], r['id'])
            if key in seen:
                if seen[key] != r:
                    raise ValueError('Conflicting action outcome')
                continue
            seen[key] = r
            rows.append(r)
    return rows


def summarize(rows):
    counts = Counter(r['outcome'] for r in rows)
    finished = [r for r in rows if r['outcome'] in TERMINAL]
    return dict(recorded=len(rows), outcomes=dict(sorted(counts.items())),
                completed=len(finished), censored=len(rows)-len(finished),
                arrival_rate=counts['ARRIVED']/len(finished) if finished else None,
                failure_rate=(counts['NO_DISPLACEMENT']+counts['BLOCKED'])/len(finished)
                if finished else None)


def report(rows):
    enriched = [r for r in rows if 'knowledge' in r]
    return dict(schema=1, features=FEATURES, overall=summarize(rows),
                knowledge_rows=len(enriched),
                blockage_causes=dict(Counter(r['blockage'] for r in enriched if r['outcome']=='BLOCKED')),
                alternative_geometry_counts=dict(Counter(str(sum(r['knowledge'][12:16])) for r in enriched)),
                by_run={run:summarize([r for r in rows if r['run']==run])
                        for run in sorted({r['run'] for r in rows})},
                warning='Accepted actions only. Incomplete actions at shutdown are unobserved; no rejected alternatives or counterfactual outcomes. Arrival is not task completion.')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('logs', nargs='+')
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    rows = read_actions(a.logs)
    if not rows or len({r['run'] for r in rows}) < 3:
        raise ValueError('Need action outcomes from three independent runs')
    result = report(rows)
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
