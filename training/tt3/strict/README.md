# Time Trial 3 — strict scaffold navigation

**Rules approved:** nine real Builder Golems, 500 Minecraft ticks (25 seconds), original unchanged `timetrial3.schem`.

A Builder may approach a valid scaffold at ground level. Once it commits to climbing a scaffold column, it must climb the **entire column** with a real native entity and collision box before moving onto the elevated walkway. No jumping is allowed. Climbing is performed by continuous vertical scaffold motion and ordinary Minecraft collision, with gravity enabled.

To return to ground level, a Builder must enter a valid scaffold tower, descend fully to its **bottom** (the scaffold center surface, 1/8 block above base), then physically step to supported ground before continuing horizontally. No mid-column sideways exits and no route advancement while the landing sequence is active.

The actual physical GameTest refuses success if a finished racer has not completed exactly one full ascent and one full descent, or any racer has left the scaffold column laterally at an intermediate height. Every generation also verifies native entity ticks. The 3-parent / 6-mutant genetics and the course's 500-tick deadline remain unchanged.

**Score isolation:** `training/tt3/strict/state/checkpoint.json` and `training/tt3/strict/replays/` are the only qualifying records for the no-jump rule. Previous `training/tt3/state/checkpoint.json` and `training/tt3/replays/` remain preserved as historical trials, but their 327-tick champion result is **not** counted as a no-jump pass. Only the inherited numeric controller genes were used to seed generation zero, not routes or scores.
