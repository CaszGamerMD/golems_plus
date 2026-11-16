# NNGolems experiment

All neural work stays on `NNGolems`. Do not merge into main until explicitly requested.

## First milestone: observation and offline learning

Enable `GOLEMS_NN_RECORD=true` (or `-Dgolems.nn.record=true`) on a test server.
The recorder emits `[NNGOLEMS]` JSON lines to the existing server log, independent
of the flight recorder toggle. Default is off. It does not steer, claim work,
request paths, change inventories, or load a model. No extra game dependency.

Each worker has one pending observation. After exactly 20 ticks under the same
controller, record net displacement and whether it is at least 0.25 metres.
Controller changes, missing ticks, and displacements over 8 metres are censored.
These are movement labels, **not success/failure or stuck labels**: idle work,
intentional waiting, loops and oscillation cannot be diagnosed from this alone.
The features are initial clipped XYZ velocity, ground contact and seven
controller indicators. No future state is fed into the model. UUID identifies
the server run; worker UUID and tick identify a sample. One server process is one
split group. The cap is 100,000 rows per server process (then recording stops).
Collection/log serialization is synchronous and may affect benchmark wall time;
compare identically instrumented runs and simulation ticks.

Collect at least three fresh controlled server runs using the same commit,
fixture and crew settings. Preserve full logs and note the commit and fixture
alongside them. Train outside Minecraft:

```
python3 scripts/nngolems/train.py run1.log run2.log run3.log --output build/nn
python3 -m unittest discover -s scripts/nngolems -p 'test_*.py'
```

The standard-library trainer uses an 11-input, 8-hidden-unit tanh network with a
sigmoid output, binary cross entropy and seeded SGD. It deduplicates observations,
rejects invalid data, and holds out entire server runs; neighbouring samples from
one run never leak across the train/test boundary. Both subsets must include
moving and stationary observations. Outputs: model.json and report.json, with
held-out accuracy, log loss, stationary recall and a training-majority baseline.
Do not tune on this held-out set repeatedly; collect new runs for final evaluation.

## What is not done yet

No trained real-world model is bundled, no in-game inference, and no improvement
in placement or navigation is claimed. Synthetic unit tests validate only the
pipeline. The present velocity features establish a deliberately small baseline;
they do not encode obstacles, destinations, inventories or construction rules.

Next: collect controlled physical traces, compare this model with a velocity-only
baseline, add local collision/goal observations and explicit action/outcome pairs,
then shadow-score safe candidate actions. Only after measured benefit should a
model rank already validated actions. Collision, reach, material, block-state,
claim ownership and escape checks remain authoritative. Any future enabled policy
needs a bounded fallback and three held-out full Matrix trials against the
conventional branch before claiming better reliability.
