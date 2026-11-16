# Team-first physical learning

Single-builder Dragon remains a strict completion regression. The new primary
experiment compares 4 and 8 builders on the exact Dragon and Dome, three
isolated trials per crew size and policy. Team-local recovery is opt-in: before
a global recall, give one currently staffed, idle, stocked worker a validated
current-phase claim. Prefer an elevated worker; keep all other claims and
physical cargo unchanged. At most one local attempt per layer; if it fails,
the existing crew recovery runs. It does not alter explicit pause behavior.

Both arms retain the validated return baseline (direct shortcut off, stocked
recovery on), record the same flight and placement actions, and use the same
frozen neural shadow advisor. The advisor still never controls movement.

Team windows record the starting crew state (alive, claimed, stocked, at depot,
elevated, nearby pairs, remaining active-phase work, retreat state), then
measure actual source-state correctness change and total crew travel at the
next sample. These are team-level observations, not proof that one action caused
the outcome. Logical completion counters are used only for local-recovery
diagnostics. A positive delta is net physical progress, not a count of all
placement attempts. Keep separate server IDs; do not random-split adjacent
windows across training and evaluation. Retain failures and timeouts. Future
team-model training must split by whole run and use held-out builds, compare
against deterministic baselines and demonstrate actual completion before
promotion. No team neural policy is trained or deployed by this workflow.
