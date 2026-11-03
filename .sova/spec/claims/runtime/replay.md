# §runtime/replay — Recording runs and replaying them for evals

A run's transcript holds enough to run it again: what it started with and every event that came
from outside. A recorded run can then be replayed against another version of its chart, which is
how real runs become eval cases.

## §runtime.replay/recording — A run records its inputs

A run's transcript records the initial data it started with (on its start row) and marks each
processed event that came from outside the chart (delivered by the host, a resume event, a human
answer) as an outside event, with its name, its data and the time on the run's clock it was
processed, in the order processed. Both are also kept as EDN beside the JSON, so a recording read
back from the transcript file keeps its keywords. Events the chart raised for itself, and the
runner's own resume signal, are not marked. A recording read from a transcript gives the initial
data, the outside events, the answers each AI step got, the path the run took and where it ended.

## §runtime.replay/replay-run — Replaying a recorded run against another version

Given a recorded run and a chart (the same version or another), a replay starts the chart with the
recorded initial data on a test clock set to the recording's start, and delivers the recorded
outside events in their recorded order, each once the chart has settled after the previous one
(timers due before an event's recorded time fire first). AI steps are answered by the recorded
run's own answers matched by step, visit and turn, by a scripted backend, or by any backend, a real
one included; tools are the ones the replay is given, so outside actions can be pretend ones. When
the new version asks an AI step the recording has no answer for, leaves recorded answers unused,
ends somewhere the recording did not, or ends with another status, the replay reports each
divergence rather than failing silently. A replay writes its own transcript and never touches the
recorded run's files.
