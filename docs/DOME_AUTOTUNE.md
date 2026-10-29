# Autonomous Physical Dome Optimizer (GitHub Actions)

This is a **non-AI** bounded navigation *heuristic optimizer*, not an autonomous
code-authoring agent. It does NOT invent new algorithms or edit Java source in
the background. It compares real physical-game outcomes and keeps the best
validated configuration for the next hourly session.

- Scheduler: `.github/workflows/dome-autotune-scheduled.yml` **on default
  branch only** (GitHub requires scheduled workflows to live on default).
- Work performed: checks out `test/dome-frontier-planner-262`, runs
  `scripts/test_dome_autotune.py`, then `scripts/dome_autotune.py`.
- Evaluator: actual `gradle --no-daemon runGameTest`, original checksum-verified
  3,537-block dome, physical worker movement and real materials; no teleport,
  mock placement, creative flight, instant build or pre-placed dome.
- Each session: runs current champion, then up to four differing candidates;
  parses *real* `[DOME PROGRESS]` counts and `[DOME TUNING]` marker.
- Changes: scan window size, number of feasible jobs checked and already-safe
  pose ranking penalties. Collision, reach, safe exit, support, line-of-sight,
  physical navigation, grapple feasibility and shared Y rule stay unchanged.
- Results: action artifact `dome-autotune-diagnostics` with per-cycle logs.
  Champion and tried settings persist on isolated
  `training/dome-autotune-state-262` branch; **no main merge**.
- Comparison: physical world blocks first, Y frontier next; compilation or
  instrumentation failure is not credited as progress. If tests regress,
  the previous verified champion remains selected.
- Up to 5 trials / 42 minutes per hourly workflow. Trigger manually from
  GitHub Actions at any time; no ChatGPT Work budget needed for the loop.
- Requires GitHub-hosted runners, repository Actions permissions allowing
  `GITHUB_TOKEN` contents writes to the isolated state branch.
- These heuristic settings affect the runner's test process only. They are
  NOT automatically promoted to production defaults. When significant
  sustained improvement is verified, review and explicitly apply it to the
  experimental mod branch before any main merge.
- A scheduler is not a substitute for code development: repeated `search_no_route`
  at a frontier with every safe reachable stance exhausted will eventually
  require a new general-purpose access/pathfinding implementation.
