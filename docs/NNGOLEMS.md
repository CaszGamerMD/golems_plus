# NNGolems experiment

All neural work stays on `NNGolems`. Do not merge into main until explicitly requested.

## Observation and offline learning (schema 2)

Enable `GOLEMS_NN_RECORD=true` (or `-Dgolems.nn.record=true`) on a test server.
The recorder emits `[NNGOLEMS]` JSON lines to the existing server log, independent
of the flight recorder toggle. Default is off. It does not steer, claim work,
request paths, change inventories, or load a model. No extra game dependency.

Each worker has one pending observation. After exactly 20 ticks under the same
controller, record net displacement and whether it is at least 0.25 metres.
Controller changes, missing ticks, and displacements over 8 metres are censored.
These are movement labels, **not success/failure or stuck labels**: idle work,
intentional waiting, loops and oscillation cannot be diagnosed from this alone.
The 21 features are initial clipped XYZ velocity, ground contact, seven
controller indicators, a destination-present bit and clipped XYZ destination
offsets (divided by 32), and six half-block collision-clearance probes.
Destinations are observed only for flight and walking; other controllers carry
an explicit missing-goal bit. Walking reports the last requested destination,
which can include pending replans. Clearance probes include a loaded-chunk guard
and are occupancy observations, not proof of an executable route or safe floor.
No future state is fed into the model. UUID identifies
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

The standard-library trainer uses an 21-input, 8-hidden-unit tanh network with a
sigmoid output, binary cross entropy and seeded SGD. It deduplicates observations,
rejects invalid data, and holds out entire server runs; neighbouring samples from
one run never leak across the train/test boundary. Both subsets must include
moving and stationary observations. Outputs: model.json and report.json, with
held-out accuracy, log loss, stationary recall, per-controller breakdowns,
a training-majority baseline, a velocity-threshold baseline (threshold selected
on training runs only), and a network with the ten new features zeroed out.
Single-class controller subsets report null stationary recall when undefined.
Do not tune on this held-out set repeatedly; collect new runs for final evaluation.

## Controlled collection

`nngolems-collect.yml` runs three independent fresh servers, each using the
original 15,376-block Matrix fixture, fixed seed/arena/worker configuration,
Creative Crate and scaffolding disabled. Each assessment has a five-minute
wall-time ceiling and may terminate earlier at a natural stall. These are short
data-collection runs, not the 25-minute full reliability benchmark.

The workflow verifies the source fixture hash and requires import, controlled
environment and terminal-result markers. `collect.py` requires at least 50 valid
observations, both classes and exactly one server-run ID per trial. Each artifact
contains full logs, compact observations, a log hash, commit, environment, class
counts and terminal outcome. The evaluation job waits for all three collections.
Data and models remain experimental artifacts; none are loaded by gameplay.

## What is not done yet

The first trained offline model and reproducible observations are preserved in
[the first-model experiment](../experiments/nngolems/2026-11-15-first-model/README.md).
It reached 97.03% held-out movement accuracy but missed all stationary flight
windows, so it is rejected for control. There is no in-game inference or claimed
improvement in placement/navigation. Synthetic tests validate only the pipeline;
the linked report distinguishes actual physical and predictive results. The observation features do not encode inventories or construction rules.
Controller names inherit the existing flight-recorder classification; the native
navigation IDLE/WALK distinction may not describe every custom movement phase.

Next: evaluate controlled physical traces, add explicit action/outcome pairs,
and shadow-score safe candidate actions. Only after measured benefit should a
model rank already validated actions. Collision, reach, material, block-state,
claim ownership and escape checks remain authoritative. Any future enabled policy
needs a bounded fallback and three held-out full Matrix trials against the
conventional branch before claiming better reliability.

## Schema 3: flight execution and recent history

New recordings use 30 inputs. The first 21 remain unchanged; seven additional
inputs observe committed path presence, remaining legs, distance to the next
waypoint, remaining route distance, controller stuck ticks, retry timer and
recent destination failure. Two further inputs encode availability and net
displacement of the immediately preceding completed 20-tick window. This
history is available at the beginning of the new window, not from its future
label. Controller transitions and tick gaps reset history. Non-flight execution
features are zero. A powered hover with no committed path can be intentional;
the network still predicts displacement, not task success.

The trainer accepts either schema 2 or 3 per dataset, never a mixture; schema
2 replay remains compatible. New reports break flight metrics down by committed
path versus no-path hover and include a simple path-presence baseline. A new
three-run collection is required; the first-model holdout remains frozen.
No steering, collision, recovery or task behavior is modified.

## Accepted flight action outcomes (action schema 1)

Schema 3 movement windows remain unchanged. Separate [NNGOLEMS_ACTION] rows
capture an accepted powered flight route, with start-only geometry, route length,
leg count and ground contact, followed by its actual terminal outcome:
ARRIVED (the existing waypoint tolerance), BLOCKED, NO_DISPLACEMENT, or a
censored cancellation/disable/replacement/observation gap. One ID per accepted
action; duplicate terminal events are prevented. The recorder is read-only and
off by default. Flight arrival does not assert landing or block placement.
Incomplete actions when a server stops are not counted; rejected requests and
unselected alternatives are not included. The report therefore estimates
observed accepted-action outcomes, not policy value or counterfactual success.

The controlled workflow now preserves actions.log and an action-report.json
with outcomes and arrival rates per server. Use these data to assess failure
coverage before fitting an action-value model; no neural recommendations yet.

Schema-3 evaluation at db28d0f3157b989a8990f551ab33fdfc9df2bfd1
([run 43808726353](https://github.com/CaszGamerMD/golems_plus/actions/runs/43808726353)):
97.77% held-out movement accuracy, 36.73% stationary-flight recall (90/245),
18.75% stationary recall on committed flight paths (3/16).
The simple no-path baseline recalls 93.47% stationary flights but only 79.46%
moving flights. Neither predicts action success. This is a new held-out
population, not a paired comparison with schema 2.

## First flight-arrival neural predictor

Frozen action training source: [run 43809737045](https://github.com/CaszGamerMD/golems_plus/actions/runs/43809737045),
3,503 terminal accepted flights (3,289 arrived, 214 blocked). No timeout
failures or censor events were recorded; actions still open at shutdown are
not observed. Three new servers are the untouched evaluation set.
The seven-input, eight-hidden-unit network estimates arrival on accepted
routes. The logistic baseline uses identical inputs, and a constant arrival
baseline exposes the class imbalance. A fixed training-only threshold
maximizes balanced accuracy; report per-run failure recall, false alarms,
log loss, and default-threshold behavior. No model may select actions yet.
The archive retrieval is pinned to the original run and requires its artifacts
to remain available; workflow fails explicitly if the archive is unavailable.
