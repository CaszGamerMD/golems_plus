# Frozen flight shadow experiment

The cross-build environmental network (run 43833585125) is loaded by
explicit opt-in only; default gameplay is unchanged. Java validates the exact
24-feature order, eight hidden units and finite parameters, logs SHA256, and
falls back to the conventional controller if loading fails. The exact accepted
flight features are reused. At real accepted takeoff only, the advisor scores
same-destination vertical-first, side-first and bounded over/under alternatives
that pass existing swept-body and envelope checks. Recommendations require
at least 0.10 predicted gain and no more than 25% extra distance (+0.02).
These are heuristic guardrails, not calibrated guarantees.

No path is replaced, rejected, delayed or executed by the model. Logs link
predictions to actual action IDs; only accepted outcomes are labeled.
Six fresh Home/Dragon trials preserve normal speed markers and report
candidate counts, recommendations and the conventional route's actual outcomes.
Both courses were training builds in the earlier fit, so these are repeat-course
runtime integration tests, not unseen-build validation. The held-out result
had only two failures in 277 flights; no claim of policy superiority. Next
step requires a controlled opt-in policy A/B test, and no model promotion
without completion/reliability evidence.
