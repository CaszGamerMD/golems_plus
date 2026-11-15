# Matrix controlled benchmark

The main reliability workflow defaults to controlled mode. All three jobs run in fresh independent GameTest servers, with the same source SHA-256, fixed world seed 260215376, fixed arena origin (1024,-57,1024), fixed schematic origin (1034,-51,1034), fixed worker UUIDs/entity IDs and per-worker random seeds, the same real chest contents and starting positions. The test-only mixin pins the GameTest world seed; a mismatch fails the run instead of silently falling back. The physical arena is loaded, cleared and verified before the job starts.

The comparator requires identical environment manifests and records wall time and game ticks. The 25-minute cutoff, natural-stall criteria, Y13/9700 minimum milestone, same-layer rule, and full 15376 source-block scoring are unchanged. Runtime and tick differences are still measured, not forced to match. JVM/Minecraft scheduling, asynchronous chunk work, and random state not controlled by the fixture may still introduce differences. The owner and job UUID are not pinned.

Use workflow_dispatch with mode=randomized to retain the independent randomly positioned arena stress test. Do not compare controlled results against randomized results as though the conditions were identical.

No production navigation or construction code is changed.
