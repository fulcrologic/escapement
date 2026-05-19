# demos/tools — the subject project

This is the Clojure project the **matrix-team** demo agents experiment on. It
is intentionally minimal: one public function (`com.example.matrix/mult`) and
one trivial test, plus a `:nrepl` alias both agents launch against.

The agents (chart at `demos/matrix_team/chart.clj`) talk to two **separate**
nREPL JVMs over this directory, each via its own service region. The
**experimenter** edits `src/com/example/matrix.clj` and benchmarks; the
**tester** writes and runs `test/com/example/matrix_test.clj`.

## Bootstrap two nREPLs

Open two terminals from this directory and start one nREPL each. Capture the
port each prints on its first line:

```bash
# terminal A — the experimenter's REPL
$ clojure -M:nrepl
nREPL server started on port 50671 on host localhost ...

# terminal B — the tester's REPL
$ clojure -M:nrepl
nREPL server started on port 50703 on host localhost ...
```

Then feed both ports into the chart via its `--input` EDN — see
`demos/matrix_team/README.md`.

## Why two JVMs?

The whole point of the demo is **isolation**: the experimenter can hot-swap
implementations and rerun benchmarks without the tester observing partial
state, and vice versa. Each REPL also has its own persistent `clj-reload`
graph for incremental reloads.
