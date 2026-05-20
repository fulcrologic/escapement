# Quickstart

## Find Running REPLs

```bash
$ clj-nrepl-eval --discover-ports
```

Output indicates source project directory and marks shadow-cljs REPLs as `(shadow)`. Multiple port files may exist.

Be sure to check an existing (CLJ) REPL to see if it is in dev/test mode for your purposes:

```bash
clj-nrepl-eval -p <port> <<EOF
(str "dev mode: " (boolean (System/getProperty "dev")))
(str "test mode: " (boolean (System/getProperty "test")))
EOF
```

## Send Commands to REPL

```bash
$ clj-nrepl-eval -p <port> <<'EOF'
  (def x 10)
  (+ x 20)
EOF
```

## Multi-Form Expressions

Always wrap multiple top-level forms in `(do ...)` when you need a single return value:

```bash
$ clj-nrepl-eval -p <port> <<'EOF'
(do
  (require '[next.jdbc :as jdbc])
  (require '[myapp.db :as db])
  (jdbc/execute-one! (db/ds) ["SELECT count(*) AS cnt FROM member"]))
EOF
```

Without `(do ...)`, `clj-nrepl-eval` evaluates each top-level form separately and produces output for each:
`=> nil\n=> nil\n=> {:cnt 42}`. Callers that parse the output get `"nil\nnil\n{:cnt 42}"` instead of just `"{:cnt 42}"`.

This is critical when piping nREPL results to other tools (Playwright, scripts, etc.).

## Start a REPL

Look in `deps.edn` for aliases. Common patterns in Fulcro projects:

```bash
# If project has :test-nrepl or :dev-nrepl alias (preferred):
$ clojure -A:test:dev -M:test-nrepl
# Writes to .nrepl-test-port

$ clojure -A:dev:cljs -M:dev-nrepl  
# Writes to .nrepl-dev-port

# If project only has :nrepl alias:
$ clojure -A:test:dev -M:nrepl

# If no nrepl alias exists, add dependency dynamically:
$ clojure -A:test:dev -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.5.1"}}}' -m nrepl.cmdline
```

**Important**: Look for these alias patterns in `deps.edn`:

- PREFER: `:nrepl`, `:test-nrepl`, `:dev-nrepl` - nREPL configurations with JVM opts
- `:test` / `:dev` - Extra paths and test/dev dependencies
- `:cljs` - ClojureScript/shadow-cljs dependencies

The local project may have special instructions for starting an nREPL.

# ClojureScript REPL (Shadow-cljs)

Shadow-cljs provides the CLJS REPL.

```clojure
;; shadow-cljs.edn may have:
{:nrepl  {:port 9000}
 :builds {:main {...}}}
```

**Requirements**: The app must be running in a browser before connecting.

```bash
# Start shadow if not running:
$ shadow-cljs watch main

# Connect to the REPL:
$ clj-nrepl-eval -p <shadow-port> <<'EOF'
  (shadow/active-builds)  ; list available builds
EOF

$ clj-nrepl-eval -p <shadow-port> <<'EOF'
  (shadow/repl :main)     ; connect to build :main
EOF
```

The CLJS mode persists across `clj-nrepl-eval` calls. Send `:cljs/quit` to return to Clojure mode.

**Note**: After source file changes, wait 1-2 seconds for hot code reload before checking results.

# Dependency Changes

Kill and restart REPL(s) when changing `deps.edn` dependencies.

# Advanced Configuration

See [project-setup.md](project-setup.md) for setting up custom nREPL configurations, guardrails, and multiple REPL
environments.
