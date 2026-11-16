# First cross-build learning experiment

Frozen archives: Matrix 43811361323, initial user courses 43832947779,
adaptive courses 43833231954. Training: three Matrix servers plus Home and
Dragon first-course data. Held out by whole schematic: both Froglights and
Farm observations, plus full Chibi adaptive observations. No held course is
used to fit weights or select thresholds. These outcomes were previously
observed, so this is a fixed generalization experiment, not a blind final exam.

Four models (movement, seven-input flight geometry, 24-input flight environment,
19-input construction) use the same fixed eight-unit network and separate
linear and always-positive baselines. Thresholds use training only. Construction
predicts controller-reported immediate completion vs wait/rejection; dependency
repair is excluded. Exact repeated contexts are collapsed for fitting only;
evaluation preserves raw decisions, not independent blocks. No-sample courses
and missing outcome classes are explicitly reported rather than invented.
The data represents conventional controllers, not a learned policy. No live
steering, no alternative route success claims, and no automated model promotion.

Physical baselines: Home 620/620, Dragon 1032/1032, Froglights 537/3102
(gate direction/interaction), Farm 11/13143 (unsupported barrel and inaccessible
frontier), Chibi 128/32061 (no work progress). These are source-match counts,
not machinery functionality. Chibi full physical assessment is now verified.
