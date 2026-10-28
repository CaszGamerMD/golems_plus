#!/usr/bin/env python3
"""Protect only native-ticked, strict no-jump/full-column Trial 3 evidence."""
import json
import sys
from pathlib import Path

oldfile, newfile, outfile = map(Path, sys.argv[1:])
RULE='scaffold-complete-up-down-no-jump-v1'
HASH='d86ad9d206729c188300460463f8e1e727c097f4291a4116292d94497d36a337'

def load(path):
    return json.loads(path.read_text()) if path.is_file() else None

previous, incoming = load(oldfile), load(newfile)
if incoming is None:
    raise ValueError('No strict no-jump real-entity evidence was generated')
for obj in [previous, incoming]:
    if obj is None:
        continue
    if (obj.get('courseHash') != HASH or obj.get('limit') != 500
            or obj.get('physicalBuilders') != 9
            or obj.get('nativeSimulationProtocol') != 'course-entity-ticking-v1'
            or obj.get('scaffoldRuleProtocol') != RULE):
        raise ValueError('Refusing cross-course/old-scaffold-rule fitness transfer')
    if obj['completedGenerations'] != len(obj['history']):
        raise ValueError('Invalid measured-generation history')
    if any(item.get('fullColumnRule') != RULE for item in obj['history']):
        raise ValueError('Generation missing strict full-height scaffold rule')
    if len(obj['nextPopulation']) != 9:
        raise ValueError('Expected nine real Builder Golem genomes')

if previous and incoming['completedGenerations'] < previous['completedGenerations']:
    chosen = previous
elif previous and incoming['completedGenerations'] == previous['completedGenerations']:
    if incoming != previous:
        raise ValueError('Equal-generation checkpoint conflict')
    chosen = previous
else:
    if previous and (incoming['history'][:len(previous['history'])] != previous['history']
                     or incoming['bestFinishers'] < previous['bestFinishers']):
        raise ValueError('Strict real-entity history or best finisher count regressed')
    chosen = incoming
outfile.parent.mkdir(parents=True, exist_ok=True)
outfile.write_text(json.dumps(chosen, indent=2) + '\n')
print('[TT3 STRICT SAVED] generations=', chosen['completedGenerations'],
      'verifiedBest=', chosen['bestFinishers'], '/9',
      'slowestChampionTicks=', chosen.get('bestCrewTicks', -1))
