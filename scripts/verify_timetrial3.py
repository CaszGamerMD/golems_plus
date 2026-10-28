#!/usr/bin/env python3
"""Fail if the physical Trial 3 GameTest alters the original user schematic."""
import hashlib
import re
from pathlib import Path
from schem_course_import import read_schematic

root=Path(__file__).resolve().parents[1]
data=(root/'training/tt3/courses/timetrial3.schem').read_bytes()
sha=hashlib.sha256(data).hexdigest()
assert sha=='d86ad9d206729c188300460463f8e1e727c097f4291a4116292d94497d36a337',sha
scheme,(w,h,l),block=read_schematic(data)
assert (w,h,l)==(27,10,12)
src=(root/'src/gametest/java/com/caszgamermd/golemsplus/builder/BuilderTimeTrial3GameTest.java').read_text()
fixture=src.split('static final String[][] LAYERS={',1)[1].split('};',1)[0]
rows=re.findall(r'"([0-9a-o]{27})"',fixture)
assert len(rows)==60, f'Expected 5 layers x 12 rows; found {len(rows)}'
palette=scheme['Blocks']['Palette']
base36='0123456789abcdefghijklmnopqrstuvwxyz'
for y in range(5):
    for z in range(12):
        for x in range(27):
            expected=base36[palette[block(x,y,z)]]
            found=rows[y*12+z][x]
            if expected!=found:
                raise AssertionError(f'Original Sponge block changed at x{x},y{y},z{z}')
for y in range(5,10):
    for z in range(12):
        for x in range(27):
            expected='minecraft:glass' if x in (0,26) or z in (0,11) else 'minecraft:air'
            assert block(x,y,z)==expected,(x,y,z,block(x,y,z))
starts=sum(block(x,0,z)=='minecraft:green_wool' for x in range(w) for z in range(l))
finishes=sum(block(x,0,z)=='minecraft:red_wool' for x in range(w) for z in range(l))
lower=sum('acacia_door' in block(x,1,z) for x in range(w) for z in range(l))
assert (starts,finishes,lower)==(20,20,7)
assert 'static final int LIMIT=500, GENERATIONS=6, COUNT=9;' in src
print(f'Exact original Trial 3 validated: {w}x{h}x{l}, {starts} starts, '
      f'{finishes} goals, {lower} closed acacia doors, 500 ticks, nine real entities')
