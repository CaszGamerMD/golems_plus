# Small-build speed benchmark

The speed suite accepts source schematics with at most 2,000 non-air blocks.
Initial fixtures: Home Demo (620) and Dragon (1,032), three independent
servers each. Real four-builder crew, verified empty arena, same source
hash, depot, initial seed, scaffold-free conventional controller and neural
observation setting. No source cell is preplaced. The score starts after the
job starts and ends when the controller completes and all source states
match under the production matcher. It includes material retrieval, walking,
flight, placement, and recovery during construction, but not final depot
return. All four workers must survive for a scored completion.

Report elapsed game ticks and blocks per game minute at 20 TPS. Wall time
is diagnostic for server performance, not builder speed. Three-run completion
rate, median, min/max and spread are reported. Failed/incomplete trials have
no speed score; comparisons require all three runs complete. A 72,000 tick
ceiling (one hour at 20 TPS) and independent 300-second wall safety limit
prevent runaway tests. Normal no-progress watchdog remains. The workflow
retains logs and training observations. No neural steering changes.

The initial baseline is not an optimization. Future policy/controller changes
should rerun identical tests and compare median ticks, completion and spread,
not cherry-pick the fastest run. New small builds can join by registering
their exact fixture and extending the benchmark matrix and report list.
