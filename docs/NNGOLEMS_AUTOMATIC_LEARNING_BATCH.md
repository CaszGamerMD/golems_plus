# Automatic multi-build NNGolems learning batch

The experimental workflow lives entirely on NNGolems:

.github/workflows/nngolems-learning-batch.yml

## Automatic triggers
A push that changes the batch workflow, neural recorder, navigation/planner,
named NN helper classes, or the training scripts automatically starts the
complete batch. A manual workflow_dispatch entry is also included. No push to
main and no merge of experimental behavior into main is performed.

**Scheduling limitation:** GitHub cron/schedule workflows run on the repository's
DEFAULT branch, not NNGolems. For that reason this workflow deliberately does
not claim to offer a working nightly/weekly cron while main stays unchanged.
A genuinely time-based GitHub schedule would require a tiny dispatcher on main
or an external scheduled caller with GitHub Actions write access.

## Design

The first batch is 3 original builds (Dragon, Dome, Farm Demo) x 3 separate
Minecraft server processes per build = 9 physical tests, each with 8 golems.
The matrix runs at most 3 servers at once and waits for all 9 results before
the cross-build evaluation. Each server uses a fixed fixture, actual Creative
Crate, full modded movement/placement, a named experiment policy, an exact
commit SHA and fresh recorder UUIDs. Timeouts, stalls and failures are data,
not silently declared completed schematics.

Flags currently enabled:
- Normal source-state and inventory verification.
- Front staging, roof flight, shape-aware escape, supported scaffold ordering,
  structure detection/lookahead and exhausted-project yield (experimental).
- Movement/action/placement recorder and team progress recorder.
- The frozen flight model's SHADOW diagnostics. It is not controlling routes.

The batch test job writes a report and validates independent server IDs, exact
world-correct counts, progress deltas and outcome strings.

Each per-run artifact (nn-learning-Course-Trial; 90 day retention) contains:
- server.log: full unedited Minecraft/GameTest execution including stuck traces.
- gradle-exit.txt: actual test-process exit code.
- manifest.json: source SHA, build, trial, run ID, team run ID, policy, source
  score, total blocks, correct percentage, environment, SHA-256 hash of log,
  counts of movement, flight, placement and team observations, failure reasons.
- samples.log: validated schema-3 [NNGOLEMS] supervised movement windows.
- actions.log: validated [NNGOLEMS_ACTION] accepted flight outcomes.
- placements.log: validated [NNGOLEMS_PLACEMENT] placement and wait outcomes.
- team.jsonl: reward/progress windows as measured physically, not guessed.
- diagnostics.jsonl: categorized blocked frontiers and recovery events; these
  are *diagnostics only*, not ground truth for training labels.
- progress.jsonl: time-series source correctness and active construction layer.

The merged nn-learning-dataset artifact (90 day retention) contains all
observation files plus summary.json, report.md, a cross-build movement-model
report, and an 8-hidden-unit offline shadow model if data quality suffices.
Team and diagnostic merged JSONL includes course, trial, held_out, commit and
the original source row for each event. Data is usable by ChatGPT or any future
training pipeline; logs are preserved for forensic review.

**Training split:** train movement on Dragon+Dome servers only. Evaluate on
all three Farm Demo servers, no overlap by server UUID or build. Training has
balanced, reproducible per-run caps so a long run cannot dominate. Both classes
are required for the model; insufficient data produces a visible skip rather
than an invented score. This model only predicts whether an accepted movement
controller moves 0.25 m in 20 ticks. It is NOT an autonomous placement learner,
and is never auto-deployed into the game.

No safety gate, schematic claims, block purchases or physical movement are
bypassed. A GitHub Actions success means tests executed and valid observations
were collected; it does not imply build completion.

## Reuse

After downloading artifacts, use:

python3 scripts/nngolems/batch_learning.py aggregate PATH_TO_DOWNLOADED_RUN_ARTIFACTS OUTPUT --commit COMMIT --expected 9

The aggregated samples/actions/placements log files retain the existing native
recorder markers and are compatible with the current offline scripts.
Retain important batches beyond the GitHub artifact retention window externally
or in a versioned data archive; this workflow does not silently accumulate
data forever or overwrite a deployed neural policy.

For an actual "learning golem," the later milestone is predicting and ranking
safe route/assignment decisions against baseline policies, then proving on new
builds that both completion rate and block-placement throughput improve.
