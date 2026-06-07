(ns stress.worst
  "TUI render stress — WORST tier (volume + concurrency).

   The brute-force case. Deterministic and MODEL-FREE: it spawns many worker
   sub-charts via the multiplex invocation (like n-subagents-demo), and each
   worker FLOODS the session with synthetic content — large artifacts, very long
   single lines, deep nested structure, many files — so the renderer is stressed
   predictably with no live model in the loop.

   What it throws at the renderer:
     * MANY concurrent worker sessions (default 12) → the inspector's invocation
       list, the live panel's per-session aggregation, and tokens/sec all get a
       crowd of rows at once.
     * Each worker writes SEVERAL artifacts (default 4 → ~48 files), so the
       artifacts view must page a long list.
     * Artifacts contain VERY LONG single lines (~4000 chars, no wrap hints),
       large token floods (hundreds of space-separated words), and DEEP nested
       structure (indented tree ~30 levels) to stress wrapping/clipping/scroll.
     * Each worker emits a burst of :artifact/captured scrollback lines in rapid
       succession (rapid-update stress on the log pane).

   It is BOUNDED: fixed worker count, fixed artifacts-per-worker, fixed sizes,
   single 'turn' per worker (script runs once on-entry, then the worker reaches
   its final). It is NOT infinite — it produces its flood and finishes, so it is
   time-boxable for capture. Tune via --param:
     --param workers=N           (default 12)
     --param artifacts-each=N    (default 4)
     --param long-line-len=N     (default 4000)
     --param flood-words=N       (default 400)

   No backend, no API key, no network. Pure CPU + filesystem:

     bb -m escapement.cli run stress.worst/agent

   (Optionally cap runtime with --param workers=6 for a faster capture.)"
  (:require
    [clojure.string :as str]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.chart :as chart]
    [com.fulcrologic.statecharts.data-model.operations :as ops]
    [com.fulcrologic.statecharts.elements
     :refer [final on-entry script state transition]]
    [com.fulcrologic.statecharts.invocation.multiplex :as mux :refer [multiplex]]
    [com.fulcrologic.statecharts.invocation.multiplex-options :as mo]
    [com.fulcrologic.statecharts.protocols :as sp]))

;; --- synthetic content generators (deterministic) -----------------------------

(defn- long-line
  "One physical line of `n` characters with no spaces — stresses horizontal
   overflow / clipping / wrap-vs-truncate."
  [n]
  (apply str (take n (cycle "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"))))

(defn- token-flood
  "`n` space-separated pseudo-words — stresses word-wrap + reflow + token count."
  [n]
  (str/join " "
    (map (fn [i] (str "tok" i "-" (apply str (repeat (inc (mod i 12)) \x)))) (range n))))

(defn- deep-tree
  "A ~`depth`-level indented tree — stresses left-margin growth + nested render."
  [depth]
  (str/join "\n"
    (for [d (range depth)]
      (str (apply str (repeat d "  ")) "└─ node level " d
        " :: " (apply str (repeat (min 40 (inc d)) \=))))))

(defn- worker-artifact
  "Build the text for artifact `k` (0-based) of a worker labelled `idx`."
  [idx k {:keys [long-line-len flood-words]}]
  (str
    "# worker " idx " — artifact " k "\n\n"
    "## very long single line (" long-line-len " chars, no spaces)\n"
    (long-line long-line-len) "\n\n"
    "## token flood (" flood-words " words)\n"
    (token-flood flood-words) "\n\n"
    "## deep nested structure\n"
    (deep-tree 30) "\n\n"
    "## a paragraph repeated\n"
    (str/join "\n"
      (repeat 40
        (str "Line of worker " idx " artifact " k " — the quick brown fox "
          "jumps over the lazy dog again and again and again.")))
    "\n"))

;; --- worker chart (model-free; floods artifacts on entry) ---------------------

(defn- flood!
  "Write `each` large synthetic artifacts for worker `idx` into the session's
   artifacts dir, emitting a rapid-fire :artifact/captured scrollback line per
   file. Pure side effects; returns nil."
  [env idx each sizes]
  (let [sdir (:escapement/session-dir env)
        tfn  (:escapement/transcript-fn env)]
    (when sdir
      (let [adir (str sdir "/artifacts")]
        (.mkdirs (java.io.File. adir))
        (dotimes [k each]
          (let [fname   (format "worker-%02d-art-%02d.md" idx k)
                content (worker-artifact idx k sizes)]
            (spit (str adir "/" fname) content)
            (when tfn
              (try (tfn {:event :artifact/captured
                         :data  {:name fname :bytes (count content)}})
                   (catch Throwable _ nil))))))))
  nil)

(def worker-chart
  (chart/statechart
    {:initial :work :name "stress-worst-worker"}
    (state {:id :work}
      (on-entry {}
        (script
          {:expr
           (fn [env data]
             (let [idx   (:idx data 0)
                   each  (:artifacts-each data 4)
                   sizes (merge {:long-line-len 4000 :flood-words 400}
                           (select-keys data [:long-line-len :flood-words]))]
               (flood! env idx each sizes)
               (mux/reply env :worker/result {:idx idx :files each})
               nil))}))
      (transition {:target :done}))
    (final {:id :done})))

(def ^:private worker-chart-id ::worker-chart)

(def ^{:multi-session? true} agent
  (chart/statechart
    {:initial :run :name "stress-worst"}
    (state {:id :run :initial :spawn}

      (on-entry {}
        (script
          {:expr
           (fn [env data]
             (sp/register-statechart!
               (::sc/statechart-registry env) worker-chart-id worker-chart)
             ;; Coerce string --param values to ints; supply defaults.
             (let [->int (fn [v d] (cond (nil? v) d
                                         (integer? v) v
                                         :else (try (Long/parseLong (str v))
                                                    (catch Throwable _ d))))]
               [(ops/assign :workers       (->int (:workers data) 12))
                (ops/assign :artifacts-each (->int (:artifacts-each data) 4))
                (ops/assign :long-line-len (->int (:long-line-len data) 4000))
                (ops/assign :flood-words   (->int (:flood-words data) 400))]))}))

      (state {:id :spawn}
        (multiplex
          {:id             :workers
           mo/child-type   ::sc/chart
           mo/count        (fn [_ data] (:workers data 12))
           mo/child-params (fn [_ data idx]
                             {:src    worker-chart-id
                              :params {:idx            idx
                                       :artifacts-each (:artifacts-each data 4)
                                       :long-line-len  (:long-line-len data 4000)
                                       :flood-words    (:flood-words data 400)}})})

        (transition {:event :worker/result :type :internal}
          (script
            {:expr (fn [_ data]
                     (let [from (get-in data [:_event :data mo/from])
                           idx  (:idx from)]
                       [(ops/assign :done-workers
                          (conj (or (:done-workers data) #{}) idx))]))}))

        (transition {:event :done.invoke.workers :target :finished}))

      (final {:id :finished}))))
