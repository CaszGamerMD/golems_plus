#!/usr/bin/env python3
"""Merge only newer, physically generated TT3 results for the SAME course."""
import json
import sys
from pathlib import Path

older,local,target=map(Path,sys.argv[1:])
def read(p):
    return json.loads(p.read_text()) if p.is_file() else None
a,b=read(older),read(local)
if b is None:
    raise ValueError('No physically observed TT3 results to persist')
PROTOCOL = 'course-entity-ticking-v1'
# The first six attempts had no native mob ticks. They are separately
# archived as pre-native-six-unqualified.json and MUST NOT become a parent,
# champion, or scored generation in the actual Minecraft training history.
if a is not None and a.get('nativeSimulationProtocol') != PROTOCOL:
    print('Ignoring pre-native unqualified TT3 checkpoint; archival retained.')
    a = None
if b.get('nativeSimulationProtocol') != PROTOCOL:
    raise ValueError('Refusing to merge TT3 without proven native entity ticks')
for obj in (a,b):
    if obj is None:continue
    assert obj['courseHash']=='d86ad9d206729c188300460463f8e1e727c097f4291a4116292d94497d36a337'
    assert obj['limit']==500 and obj['physicalBuilders']==9
    assert len(obj['nextPopulation'])==9
    assert obj['completedGenerations']==len(obj['history'])
    assert all('finishedBy500Ticks' in row for row in obj['history'])
if a and a['completedGenerations']>b['completedGenerations']:
    chosen=a
elif a and a['completedGenerations']==b['completedGenerations']:
    if a!=b:raise ValueError('Conflicting scored TT3 checkpoints at same generation')
    chosen=a
else:
    if a:
        assert b['history'][:len(a['history'])]==a['history'], 'Training history regressed'
        assert b['bestFinishers']>=a['bestFinishers'], 'Discarded completed crew proof'
    chosen=b
target.parent.mkdir(parents=True,exist_ok=True)
target.write_text(json.dumps(chosen,indent=2)+'\n')
print('Preserved real TT3 generations:',chosen['completedGenerations'],
      'best crew:',chosen['bestFinishers'],'/9')
