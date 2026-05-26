# `llm-conversation` (and `human-input`) authoring API — flat keys, no `params-fn`

## Context

`escapement.chart.helpers/llm-conversation` is the authoring sugar charts use to
attach an LLM conversation to a state. Today its shape is:

```clojure
(h/llm-conversation
  {:id        "planning-llm"
   :params-fn (fn [env data]
                {:system                       "You are an expert engineer…"
                 :allowed-events               []
                 :max-turns                    1
                 :max-conversation-duration-ms 120000
                 :initial-user-message         (render-prompt data)})})
```

`:params-fn` is `(fn [env data] => params-map)`; the helper passes it straight
through as the invoke's `:params`, and the `:llm-conversation` InvocationProcessor
(`escapement.invocation.llm-conversation`) calls it at invoke time and consumes
the returned map. `human-input` and `with-llm-questions` use the same
`:params-fn` shape.

### Why this is awkward (the problem)

1. **The contract lives off-site.** The helper accepts an *opaque map* whose keys
   are defined and validated inside the processor, not the helper. The processor
   consumes a large surface:

   - conversation control: `:initial-user-message`, `:initial-messages`,
     `:max-turns`, `:max-conversation-duration-ms`, `:allowed-events`,
     `:real-tools`, `:chart-tools`, `:stream?`
   - request shaping: `:system`, `:model`, `:models`, `:needs`, `:temperature`,
     `:top-p`, `:top-k`, `:stop-sequences`, `:thinking`, `:tool-choice`,
     `:metadata`, `:system-cache-control`, `:tools-cache-control`, `:resilience`,
     `:verdict-schema`

   The helper's docstring can't own any of this — it punts ("see the
   processor"). A caller types `{…}` with no schema, no completion, no doc where
   they are looking.

2. **`-fn` is not the library idiom.** Everywhere else the engine takes a single
   *expression* slot `(fn [env data])` (`:expr` on `script`, `:cond` on
   `transition`, `:srcexpr`/`:params` on `invoke`). It never bolts a `…-fn` key
   onto the element. `:params-fn` is a second-class re-spelling of the invoke's
   own `:params` expression.

3. **It forces a closure over constants.** In practice only
   `:initial-user-message` (sometimes `:system`) is data-dependent; `:max-turns`,
   `:allowed-events`, `:model` are static literals. `:params-fn` forces wrapping
   the *entire* option set in a lambda, burying the one dynamic slot inside four
   that aren't:

   ```clojure
   :params-fn (fn [_ data] {:max-turns 1 :allowed-events [] :model "…"
                            :initial-user-message (render data)})
   ```

4. **Nowhere to validate.** One opaque return map ⇒ no per-key Malli, no
   "you typo'd `:max-turn`", no arity help.

## Locked decisions

- **Flatten every key onto the element**, each accepting **a literal OR a
  `(fn [env data])`**, resolved at invoke time. "Value or expression" is the
  library's own idiom; resolution is `(if (fn? x) (x env data) x)` per key.
- **No backward compatibility. `:params-fn` and `:params` are removed.** This
  project has no external users yet (the consumers are us); call sites are ported
  in the same change. A clean, single-idiom surface beats a dual codepath.
- **No escape-hatch map.** Every processor key is reachable as a flat key
  (literal-or-fn); the curated table is just the *documented* subset. Uncurated
  processor keys (`:temperature`, `:thinking`, cache-control, …) still flow
  through verbatim under the same literal-or-fn rule — they are simply not in the
  doc table. There is no "supply the whole map yourself" key.
- **Friendly aliases** for the two awkward processor keys:
  `:message` → `:initial-user-message`, `:budget-ms` →
  `:max-conversation-duration-ms`. The canonical processor names still work as
  flat keys (they pass through); the aliases are the *recommended* spelling.
- **`raw-keys` bypass for legitimately function-valued params.** `fn?` is the
  "compute me" signal, so a param whose *value* is itself a function cannot be a
  flat literal. `:llm-conversation` has no such param. `:human-input` does:
  `:render` is `(fn [env data] answer)` the processor *calls*. The helper keeps a
  per-helper `raw-keys` set (`#{:render}` for human-input, `#{}` for
  llm-conversation) whose members pass through untouched.
- **The processors are NOT changed.** This is purely authoring sugar in
  `helpers.cljc`: assemble + resolve the authored keys into the `params` map each
  invoke already expects.
- Lands in **0.0.2-SNAPSHOT** as a **breaking change**.

## Target API

```clojure
;; Curated, self-documenting, static-stays-static, one dynamic slot is a lambda:
(h/llm-conversation
  {:id             "planning-llm"
   :system         "You are an expert engineer. Output only the plan."
   :message        (fn [_ data] (prompts/render :planning (plan-subs data)))
   :max-turns      1
   :allowed-events []})

;; Any key may be a literal or a (fn [env data]); e.g. a data-driven model pick:
{:model (fn [_ data] (:chosen-model data)) …}

;; Long-tail / advanced keys still available verbatim (not first-class doc'd):
{:id "x" :message m :temperature 0.2 :thinking {…} :needs {…}}

;; human-input: :render is a raw function param (NOT resolved against env/data):
(h/human-input
  {:id     "confirm"
   :kind   :confirm
   :prompt (fn [_ data] (str "Delete " (:file data) "?"))
   :render (fn [env data] …)})        ;; passes through untouched
```

There is no `:params` / `:params-fn` key. To compute several params from `data`,
write a `(fn [env data])` per key (they may share a helper, e.g.
`(fn [_ data] (:system (my-params data)))`), or — preferred — make the static
keys literals and only the genuinely data-dependent ones lambdas.

### Resolution semantics

At invoke time the helper builds the `params` map the processor consumes:

1. Drop the control keys the helper owns (`:id`, and for llm-conversation
   `:autoforward?`); for `with-llm-questions` also `:exit-transitions`.
2. For each remaining key `k`/value `v`:
   `v' = (if (and (fn? v) (not (raw-keys k))) (v env data) v)`.
   Keys in `raw-keys` pass through untouched.
3. Apply aliases: `:message`→`:initial-user-message`, `:budget-ms`→
   `:max-conversation-duration-ms`. If both the alias and the canonical key are
   present, the canonical key wins and the alias is dropped.

Caveat (document it): because `fn?` is the "compute me" signal, a param whose
legitimate value is a function must be listed in `raw-keys`. The only such param
today is human-input's `:render`.

### Curated key set — `llm-conversation` (helper owns docstring + schema)

| Authored key | → processor key | Typical | Notes |
|---|---|---|---|
| `:id` (req) | invoke id | static | control key, not a param |
| `:system` | `:system` | literal or fn | |
| `:message` | `:initial-user-message` | usually fn | friendly alias |
| `:max-turns` | `:max-turns` | static | |
| `:budget-ms` | `:max-conversation-duration-ms` | static | friendly alias |
| `:allowed-events` | `:allowed-events` | static | |
| `:real-tools` | `:real-tools` | static | |
| `:chart-tools` | `:chart-tools` | static | |
| `:model` / `:models` / `:needs` | same | static | model policy |
| `:verdict-schema` | `:verdict-schema` | static | wrap-up inference |
| `:autoforward?` | invoke `:auto-forward?` | static (default true) | control key |

Everything else the processor accepts (`:temperature`, `:thinking`,
`:tool-choice`, `:resilience`, `:stream?`, cache-control, …) passes through
verbatim via the same literal-or-fn rule; it's just not in the curated table.

### Curated key set — `human-input`

| Authored key | → processor key | Typical | Notes |
|---|---|---|---|
| `:id` (req) | invoke id | static | control key |
| `:kind` | `:kind` | static | `:text`/`:select`/`:multi-select`/`:confirm`/`:progress`/`:custom` |
| `:prompt` | `:prompt` | literal or fn | |
| `:options` | `:options` | literal or fn | `:select`/`:multi-select` |
| `:answer-schema` | `:answer-schema` | static | Malli |
| `:on-answer-event` | `:on-answer-event` | static | default `:human.answer` |
| `:on-cancel-event` | `:on-cancel-event` | static | default `:human.cancelled` |
| `:render` | `:render` | **raw fn** | required for `:custom`; in `raw-keys`, never resolved |

## Implementation sketch (helpers.cljc)

```clojure
(def ^:private llm-aliases
  {:message :initial-user-message :budget-ms :max-conversation-duration-ms})

(defn- apply-aliases [m aliases]
  (reduce-kv (fn [m a c]
               (if (contains? m a)
                 (cond-> m
                   (not (contains? m c)) (assoc c (get m a))
                   true                  (dissoc a))
                 m))
             m aliases))

(defn- resolve-flat-params
  "Resolve flat authoring `opts` into the params map a processor consumes.
   Drops `drop-ks`; for each remaining key applies value-or-expression
   `(if (fn? v) (v env data) v)` EXCEPT keys in `raw-ks` (which pass through
   untouched — they are legitimately function-valued, e.g. :render); then
   applies `aliases` (canonical wins if both present)."
  [opts env data {:keys [drop-ks raw-ks aliases]}]
  (let [named    (apply dissoc opts drop-ks)
        resolved (reduce-kv
                   (fn [m k v]
                     (assoc m k (if (and (fn? v) (not (contains? raw-ks k)))
                                  (v env data) v)))
                   {} named)]
    (apply-aliases resolved aliases)))

(defn llm-conversation
  "…docstring OWNS the curated key table; each value may be a literal or
   (fn [env data]); no :params/:params-fn — flatten keys directly…"
  [{:keys [id autoforward?] :or {autoforward? true} :as opts}]
  (assert id "llm-conversation requires :id")
  (elt/invoke {:type :llm-conversation :id id
               :autoforward autoforward? :auto-forward? autoforward?
               :params (fn [env data]
                         (resolve-flat-params opts env data
                           {:drop-ks [:id :autoforward?] :raw-ks #{}
                            :aliases llm-aliases}))}))

(defn human-input
  "…flat keys; :render passes through as a raw function…"
  [{:keys [id] :as opts}]
  (assert id "human-input requires :id")
  (elt/invoke {:type :human-input :id id
               :params (fn [env data]
                         (resolve-flat-params opts env data
                           {:drop-ks [:id] :raw-ks #{:render} :aliases nil}))}))
```

`with-llm-questions` keeps its compound-state shape, but instead of wrapping a
caller-supplied `params-fn` it injects the two question events into the flat
`:allowed-events` (resolving it first if a fn) and forwards the remaining flat
keys to `llm-conversation`.

(Optional, dev-time: a Malli schema for the curated keys, checked under
Guardrails so a typo'd/ill-typed authored key fails fast at build. Kept lenient
in prod; unknown keys are allowed because uncurated processor keys pass through.)

## Migration (breaking)

`:params-fn` / `:params` no longer exist. Port each call site by lifting the keys
out of the returned map onto the element:

```clojure
;; before
(h/llm-conversation
  {:id "scanner"
   :params-fn (fn [_ data]
                {:system         sys
                 :real-tools     [:fs/read]
                 :allowed-events events
                 :initial-user-message (user-message data)})})

;; after
(h/llm-conversation
  {:id             "scanner"
   :system         sys
   :real-tools     [:fs/read]
   :allowed-events events
   :message        (fn [_ data] (user-message data))})
```

Call sites that built the whole map from `data` via a helper
(`(experimenter-params data)`) inline the static keys as literals and keep only
the data-dependent keys as lambdas.

## Test plan (`test/escapement/chart/helpers_llm_conversation_test.clj` + human-input)

A focused unit test on `resolve-flat-params` (pure) covers most of these without
a backend:

1. literal-only opts → params map has the literals, aliases applied.
2. per-key fn opts → resolved against `(env data)`.
3. mixed literal + fn.
4. `:message`/`:budget-ms` aliases map to canonical processor keys; explicit
   canonical key is not clobbered (canonical wins).
5. `raw-ks` (`:render`) → function passes through untouched, NOT called.
6. control keys (`:id`, `:autoforward?`) are dropped from the params map.
7. uncurated key (`:temperature`) passes through (literal and fn).
8. (if schema added) a bad key/type fails under Guardrails.

## Tasks

- [ ] `helpers.cljc`: add `apply-aliases` + `resolve-flat-params`; rewrite
      `llm-conversation`, `human-input`, `with-llm-questions`; full docstrings
      with the curated key tables.
- [ ] (optional) Malli schema for curated keys; Guardrails check.
- [ ] Tests above + port `helpers_test.clj`, `human_input_test.clj`.
- [ ] Port every call site (`src/escapement/examples/**`, `src/escapement/chart/consult.cljc`,
      `demos/**`, `test/**`) from `:params-fn` to flat keys.
- [ ] `CHANGELOG.md`: 0.0.2 — **BREAKING**: "llm-conversation/human-input/
      with-llm-questions take flat, literal-or-fn keys
      (`:system`/`:message`/`:max-turns`/…); `:params`/`:params-fn` removed."
- [ ] (docs) note the pattern in the helper ns docstring / Guide; update demo
      READMEs / embed_example commentary that describe `:params-fn`.

## Open questions (resolved)

1. **Validation strictness** — Guardrails-only (dev); prod lenient (unknown keys
   pass through to the processor).
2. **Message key name** — `:message` (`:initial-user-message` retained as the
   canonical/processor name and still accepted as a flat key).
3. **Escape-hatch precedence** — N/A; there is no escape hatch.
