"""Validate and preserve the observations plus provenance from one physical run."""
import argparse
import hashlib
import json
from pathlib import Path
from train import read_rows, MARKER
from actions import read_actions, MARKER as ACTION_MARKER, summarize
from placements import read_placements, MARKER as PLACEMENT_MARKER


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
    if len(manifests) != 1 or len(outcomes) != 1:
        raise ValueError('Missing or ambiguous controlled environment/result')
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output/'samples.log').write_text(''.join(MARKER+json.dumps(r)+'\n' for r in rows))
    (args.output/'actions.log').write_text(''.join(ACTION_MARKER+json.dumps(r)+'\n' for r in actions))
    (args.output/'placements.log').write_text(''.join(PLACEMENT_MARKER+json.dumps(r)+'\n' for r in placements))
    provenance = dict(placement_observations=len(placements), action_outcomes=summarize(actions), commit=args.commit, trial=args.trial, schema=rows[0]['schema'],
                      run=rows[0]['run'], samples=len(rows),
                      moving=sum(r['moved'] for r in rows),
                      stationary=sum(not r['moved'] for r in rows),
                      log_sha256=hashlib.sha256(raw).hexdigest(),
                      environment=manifests[0], result=outcomes[0])
    (args.output/'provenance.json').write_text(json.dumps(provenance, indent=2))
    print(json.dumps(provenance, indent=2))


if __name__ == '__main__':
    main()
