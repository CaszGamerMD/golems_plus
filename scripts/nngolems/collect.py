"""Validate and preserve the observations plus provenance from one physical run."""
import argparse
import hashlib
import json
import re
from pathlib import Path
from train import read_rows, MARKER
from actions import read_actions, MARKER as ACTION_MARKER, summarize
from placements import read_placements, MARKER as PLACEMENT_MARKER



def resolve_terminal_results(outcomes):
    """Recover only the known one-tick GameTest stall/timeout reporting race.

    Never silently choose unrelated terminal results. The first event is the
    actual stall; a later TIME_LIMIT is a callback following GameTest fail().
    Retain both events in provenance so this is not mistaken for two runs.
    """
    if len(outcomes) == 1:
        return outcomes[0], []
    if len(outcomes) != 2:
        raise ValueError('Missing or ambiguous controlled environment/result')
    parsed = [dict(re.findall(r'([A-Za-z][A-Za-z0-9]*)=([^ ]+)', row))
              for row in outcomes]
    a, b = parsed
    if (a.get('outcome') not in ('ALL_BUILDERS_STALLED', 'NO_WORK_PROGRESS')
            or b.get('outcome') != 'TIME_LIMIT'
            or any(a.get(key) is None or a.get(key) != b.get(key)
                   for key in ('layer', 'correct', 'total', 'best', 'unsupported'))):
        raise ValueError('Conflicting controlled terminal results')
    try:
        tick_gap = int(b['tick']) - int(a['tick'])
        elapsed_gap = int(b['elapsedMs']) - int(a['elapsedMs'])
    except (KeyError, ValueError) as exc:
        raise ValueError('Invalid duplicate result timestamps') from exc
    if not (0 <= tick_gap <= 2 and 0 <= elapsed_gap <= 2000):
        raise ValueError('Unrelated controlled terminal results')
    return outcomes[0], outcomes[1:]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('log', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('--commit', required=True)
    p.add_argument('--trial', required=True)
    args = p.parse_args()
    raw = args.log.read_bytes()
    rows = read_rows([args.log])
    if len(rows) < 50 or len({r['run'] for r in rows}) != 1:
        raise ValueError('Expected at least 50 observations from exactly one fresh server')
    if {r['moved'] for r in rows} != {0, 1}:
        raise ValueError('Run must include moving and stationary observations')
    actions = read_actions([args.log])
    if not actions or {r['run'] for r in actions} != {rows[0]['run']}:
        raise ValueError('Missing or mismatched flight action outcomes')
    placements = read_placements([args.log])
    if not placements or {r['run'] for r in placements} != {rows[0]['run']}:
        raise ValueError('Missing or mismatched construction observations')
    lines = raw.decode().splitlines()
    manifests = [line.split('[MATRIX CONTROLLED ENV] ', 1)[1]
                 for line in lines if '[MATRIX CONTROLLED ENV] ' in line]
    outcomes = [line.split('[MATRIX TRIAL RESULT] ', 1)[1]
                for line in lines if '[MATRIX TRIAL RESULT] ' in line]
    if len(manifests) != 1:
        raise ValueError('Missing or ambiguous controlled environment/result')
    result, terminal_followups = resolve_terminal_results(outcomes)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output/'samples.log').write_text(''.join(MARKER+json.dumps(r)+'\n' for r in rows))
    (args.output/'actions.log').write_text(''.join(ACTION_MARKER+json.dumps(r)+'\n' for r in actions))
    (args.output/'placements.log').write_text(''.join(PLACEMENT_MARKER+json.dumps(r)+'\n' for r in placements))
    provenance = dict(placement_observations=len(placements), action_outcomes=summarize(actions), commit=args.commit, trial=args.trial, schema=rows[0]['schema'],
                      run=rows[0]['run'], samples=len(rows),
                      moving=sum(r['moved'] for r in rows),
                      stationary=sum(not r['moved'] for r in rows),
                      log_sha256=hashlib.sha256(raw).hexdigest(),
                      environment=manifests[0], result=result,
                      terminal_race=bool(terminal_followups),
                      terminal_followups=terminal_followups)
    (args.output/'provenance.json').write_text(json.dumps(provenance, indent=2))
    print(json.dumps(provenance, indent=2))


if __name__ == '__main__':
    main()
