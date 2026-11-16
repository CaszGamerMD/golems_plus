# First real-data movement model — 2026-11-15 (America/Chicago)

Experimental baseline only. **Not approved for movement or recovery control.**

Source commit: `ed51623659e23ba703ca3277e039765a38dafbcf` on NNGolems.
Collection and evaluation: https://github.com/CaszGamerMD/golems_plus/actions/runs/43807121267
The four pipeline unit tests, CLI smoke check, Java compilation and all three
collection jobs passed. A successful collection job is not a completed build.

## Physical runs

Three fresh servers, same controlled environment manifest and original source
fixture SHA-256 `77fa9731b11b1b666034a9e155c5a56abc173b9f9a974f963f916d7ef7d11816`.
Four builders, Creative Crate, temporary scaffolding disabled. Each run had a
300-second ceiling, with earlier termination on the existing stall criteria.
The environment marker's legacy `cap=1500` field is hardcoded by the fixture;
the actual run limit is 300 seconds, as recorded in each full log's trial-start
marker. These are observational runs, not a neural-policy build comparison.

| Trial | Terminal outcome | Wall seconds | Game ticks | Layer | Correct / 15,376 | Samples |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1 | Time limit | 300.176 | 72,778 | 12 | 9,706 | 9,114 |
| 2 | All builders stalled | 246.363 | 79,110 | 13 | 10,863 | 10,210 |
| 3 | All builders stalled | 268.100 | 75,275 | 12 | 9,802 | 9,928 |

29,252 total observations: 10,683 moving and 18,569 stationary. Two unsupported
source states were reported by each assessment. Gameplay uses the existing
conventional controller throughout; these counts are not neural improvements.

## Frozen evaluation

Train on trials 1 and 3 (19,042 observations), hold out trial 2 (10,210).
No parameter tuning on the held-out run. Same fixture across runs; this evaluates
repeat-run generalization, not new schematics or environments.

| Predictor | Held-out accuracy |
| --- | ---: |
| Training-majority class | 62.85% |
| Velocity threshold selected on training data | 87.11% |
| Network with obstacle/destination inputs zeroed | 90.85% |
| Full 21-input network | 97.03% |

Full model log loss: 0.08609. Training accuracy: 97.27%.
Confusion: 6,162 stationary correctly predicted; 255 stationary predicted moving;
3,745 moving correctly predicted; 48 moving predicted stationary.

**Critical rejection:** all 253 held-out stationary FLIGHT windows were predicted
moving (0% stationary-flight recall). Median actual displacement in those windows
was zero. Overall accuracy conceals this weakness. Walking stationary recall was
350/351, but stationary is not synonymous with failure: waiting and legitimate
work can require standing still. The model is not a stall detector.

Coverage: FLIGHT 8,676; GRAPPLE 52; IDLE 16,148; WALK 4,358; YIELD 18.
No SCAFFOLD or CENTER examples. Only 234 IDLE observations moved; the coarse
native-navigation label remains a known limitation. Every WALK and FLIGHT sample
had a destination observation. Short controller transitions were censored, so
these figures apply only to stable 20-tick controller windows.

## Reproduce and preserve

`model.json` contains the actual trained weights; `report.json` is the original
CI evaluation. `provenance.json` records all three server IDs, environment,
source commit, terminal outcomes and full-log hashes. Compressed sample logs
preserve the exact training/evaluation inputs in Git, independent of CI artifact
expiry. Full server logs remain in the linked workflow artifacts.

From the repository root:

```bash
mkdir -p build/nn-replay
for trial in 1 2 3; do
  gzip -dc experiments/nngolems/2026-11-15-first-model/trial-${trial}-samples.log.gz > build/nn-replay/trial-${trial}.log
done
python3 scripts/nngolems/train.py build/nn-replay/*.log --output build/nn-replay/result --commit ed51623659e23ba703ca3277e039765a38dafbcf
```

Next experiment: add flight execution state (remaining path, retry/landing phase,
recent displacement and time without progress), create focused stationary-flight
and legitimate-hover examples, and evaluate on fresh held-out runs. Do not tune
this model on trial 2 and call it a fresh validation. Action recommendations and
in-game inference remain deferred until this failure is addressed.
