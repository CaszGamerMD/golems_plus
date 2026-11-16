# Failure-balanced, rotation-invariant flight learning

This experiment follows the 2026-11-16 test results. It changes neural training
and opt-in shadow inference only. Main and conventional movement are untouched.

Evidence motivating the experiment:
- Latest accepted-flight geometry model (run 43860868337) had 539 failed actions
  in 4,571 held-out actions: 77.78% balanced accuracy vs the linear model's 85.73%.
- Prior environmental model's held-out set contained only two failures among
  277 flights; its 99.45% balanced accuracy did not establish broad reliability.
- The feature vector encodes absolute X/Z directions. Equivalent rotated scenes
  should not receive different neural scores. This does NOT establish that the
  earlier physical X/Z performance difference was caused by a neural model:
  conventional strategy and recovery logic still owns all movement.

Changes:
1. Give each outcome equal training mass, divided equally across servers that
   contain it. Preserve individual action labels and their provenance.
2. Generate four Y-axis rotations of each training scene, rotating both signed
   displacement and start/end collision probes. Do not invent new outcomes.
3. Average the four rotated predictions at inference for models explicitly
   declaring `symmetry: c4_y`. Legacy models retain their original scoring.
4. Validate Python/Java rotations and invariant outputs. Reject unsupported
   symmetry tags and nonfinite runtime inputs. No model is enabled by default.

Fixed experimental protocol:
- Training archives: original Matrix, Home and Dragon as used by cross-build
  run 43833585125; add latest Matrix trials 1 and 2 from 43860868337.
- Evaluation: original Froglights/Farm/Chibi plus latest Matrix trial 3.
- Baseline: frozen 24-input environmental model from 43833585125.
- Candidate: eight hidden units, seed 17, 40 epochs, learning rate .01,
  training-only decision threshold. These choices precede evaluation.
- The comparison combines additional failure data, balancing and symmetry;
  it cannot attribute a score change to just one of those changes.
- Whole server IDs are checked for disjointness. These archives were previously
  observed, so the experiment is retrospective validation, not a blind final test.
- Predicted arrival is not successful placement, job completion or rescue.
  Rotated copies do not increase the number of independent failure examples.
- Weighted model scores are not calibrated success probabilities.

Runtime remains shadow-only. No path is changed, rejected, delayed or executed
by the network. Current Dragon/Dome recovery regressions are separate blockers
for a gameplay-improvement release. The artifact is a diagnostic testing build.

## Observed results

Training: 8,801 real actions from seven servers, including 807 failures.
Workflow: https://github.com/CaszGamerMD/golems_plus/actions/runs/43862696281
Python suite: 16 tests passed. Java advisor tests and mod/fixture compilation passed.

| Evaluation | Old balanced accuracy | New balanced accuracy | Old/new missed failures | Old/new false alarms |
| --- | ---: | ---: | ---: | ---: |
| Reserved Matrix trial 3 (1,153 actions, 41 failures) | 97.20% | 98.29% | 2 / 1 | 8 / 11 |
| Froglights (270 actions, 2 failures) | 99.44% | 99.25% | 0 / 0 | 3 / 4 |

Chibi: seven arrivals, no failures; insufficient coverage for failure recall.
Farm: no accepted flight observations, no predictive conclusion.
The baseline's maximum score spread over equivalent rotations was 0.91644 on
reserved Matrix scenes; the candidate spread was exactly zero. This is a direct
invariance improvement. Raw Matrix accuracy fell from 99.13% to 98.96%, and
log loss worsened from .02515 to .03405. Do not describe the model as uniformly
better or calibrated. It remains a shadow-testing candidate, not a promoted policy.

The earlier 77.78% result was for a different seven-input geometry model on all
three newer Matrix runs. The table above compares the frozen 24-input model and
new 24-input model on the SAME reserved trial. These percentages are not interchangeable.
