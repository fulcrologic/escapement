(ns escapement.ui.tui-render-test
  "Headless integration test that proves the SHARED RAD explorer screens render against Escapement's
   real Pathom read resolvers — with NO browser. A real client app (statechart routing + the RAD
   SessionsReport / EventsReport / ArtifactsReport + the fulcro-tui rendering plugin) is wired to
   `escapement.ui.resolvers/process` through an in-process loopback remote (no HTTP). The app paints
   to a `string-terminal`; assertions poll the painted screen.

   This is the primary verification vehicle for the explorer UI: it exercises the sessions list →
   drill-in to a session's events/artifacts exactly as a terminal user would see them. JVM-only
   (RAD + fulcro-tui stack); excluded from `bb test` (see runner's `jvm-only-namespaces`) and run via
   `clojure -M:ui-test`.

   Loads/routes are asynchronous (the statechart event loop + the loopback remote callback), so the
   helpers POLL the rendered screen until the expected content appears rather than sleeping a fixed
   time."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.headless.loopback-remotes :as lb]
    [com.fulcrologic.fulcro.tui.application :as tui-app]
    [com.fulcrologic.fulcro.tui.engine :as engine]
    [com.fulcrologic.fulcro.tui.terminal :as term]
    [com.fulcrologic.guardrails.config :as grc]
    [com.fulcrologic.rad.application :as rad-app]
    [com.fulcrologic.statecharts :as-alias sc]
    [com.fulcrologic.statecharts.integration.fulcro :as scf]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as scr]
    [escapement.protocols :as proto]
    [escapement.ui.rendering.tui.plugin :as tui-plugin]
    [escapement.ui.rendering.tui.root :as tui-root]
    [escapement.ui.rendering.tui.routing :refer [routing-chart]]
    [escapement.ui.resolvers :as r]
    [fulcro-spec.core :refer [=> assertions specification]]
    [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Fixture store (reused from escapement.ui.resolvers-test — an in-memory read
;; store reified from EDN, isolating the resolvers from disk).
;; ---------------------------------------------------------------------------

(defn- stub-store
  "Reify the read protocols from in-memory data. `sessions` is a vector of summaries; `events` and
   `artifacts` are `{session-id [...]}`; `contents` is `{session-id {path content}}`."
  [{:keys [sessions events artifacts contents]}]
  (reify
    proto/SessionIndex
    (list-sessions [_] sessions)
    proto/TranscriptStore
    (append-event! [_ _ _] (throw (ex-info "read-only" {})))
    (read-events [_ sid query]
      (cond->> (get events sid [])
        (:types query)    (filter #(contains? (:types query) (:transcript/kind %)))
        (:from-seq query) (filter #(<= (:from-seq query) (:transcript/seq %)))
        (:limit query)    (take (:limit query))
        true              vec))
    proto/ArtifactStore
    (write-artifact! [_ _ _ _ _] nil)
    (list-artifacts [_ sid] (get artifacts sid []))
    (read-artifact [_ sid path] (get-in contents [sid path]))))

(def fixture
  "Two sessions; session \"s1\" carries a transcript (3 events, kinds runner/started, llm/request,
   runner/done) and two artifacts (an author report + a captured-IO request)."
  {:sessions  [{::sc/session-id "s1" ::sc/statechart-src :examples/writer :session/status :done
                :session/started-at 200 :session/ended-at 260 :session/event-count 3}
               {::sc/session-id "s2" ::sc/statechart-src :examples/hello :session/status :incomplete
                :session/started-at 100 :session/event-count 1}]
   :events    {"s1" [{:transcript/seq 0 :transcript/ts 200 :transcript/kind :runner/started :transcript/data {}}
                     {:transcript/seq 1 :transcript/ts 230 :transcript/kind :llm/request
                      :io/ref "nodes/w/0/turns/0/request.edn" :io/snippet "give me a haiku"}
                     {:transcript/seq 2 :transcript/ts 260 :transcript/kind :runner/done :transcript/data {}}]}
   :artifacts {"s1" [{:artifact/class :author :artifact/path "artifacts/report.md" :artifact/size 4
                      :artifact/content-type "text/markdown"}
                     {:artifact/class :captured-io :artifact/path "nodes/w/0/turns/0/request.edn"
                      :artifact/size 42 :artifact/content-type "application/edn"
                      :transcript/node-id :w :transcript/visit 0 :transcript/turn 0}]}
   :contents  {"s1" {"artifacts/report.md" "# hi"}}})

;; ---------------------------------------------------------------------------
;; Screen helpers (poll the painted terminal — loads are async)
;; ---------------------------------------------------------------------------

(defn- screen-text
  "Returns the painted screen of `app` as a single newline-joined string (trailing blanks trimmed)."
  [app]
  (tui-app/render! app)
  (str/join "\n" (mapv str/trimr (tui-app/screen-of app))))

(defn- wait-for
  "Renders `app` and returns the screen text once it matches `re`, polling up to ~20s. Returns the
   last screen on timeout (so the assertion fails with the real screen, not a hang)."
  [app re]
  (loop [n 0]
    (let [screen (screen-text app)]
      (if (or (re-find re screen) (>= n 80))
        screen
        (do (Thread/sleep 250) (recur (inc n)))))))

;; The `:ui-test` alias runs guardrails in `:runtime` mode (`-Dguardrails.enabled=true`). The
;; fulcro-tui engine's render/place/paint fns are heavily `>defn`-instrumented over LARGE data
;; structures (deep node trees + a full cell buffer — `::node`/`::buffer`/`::cell` specs). Conforming
;; those on every engine call makes a single `render!` take many SECONDS-to-minutes, which (compounded
;; by the polling `wait-for`) looks like an infinite hang. Excluding the two hot engine namespaces from
;; runtime checks drops a render from minutes to ~25ms while leaving guardrails on for everything else
;; (RAD, resolvers, our render fns). Done once, lazily, the first time an app is built.
(defonce ^:private engine-checks-excluded
  (do
    (grc/exclude-checks! 'com.fulcrologic.fulcro.tui.engine)
    (grc/exclude-checks! 'com.fulcrologic.fulcro.tui.application)
    true))

(defn- test-app
  "Builds a headless explorer client app whose remote calls the Escapement read resolvers in-process
   (via `r/process` over the fixture store), installs the fulcro-tui RAD render plugin + the
   statechart engine + routing chart, attaches a `string-terminal`, and returns it."
  []
  engine-checks-excluded
  (let [store (stub-store fixture)
        ctx   {:escapement/store store :escapement/active-session-id "s1"}
        app   (tui-app/application
                {:root-class tui-root/Root
                 :remotes    {:remote (lb/sync-remote (fn [eql] (r/process ctx eql)))}})]
    (rad-app/install-ui-controls! app tui-plugin/all-controls)
    ;; `:event-loop? true` (async go-loop): a report load completes via a network callback that then
    ;; sends `:event/loaded`; the loop drains that follow-on event so the report advances
    ;; load → process → ready. `:immediate` only runs the directly-dispatched event and the report
    ;; stays busy with no processed rows.
    (scf/install-fulcro-statecharts! app {:event-loop? true})
    (scr/start! app routing-chart)
    (tui-app/attach! app (term/string-terminal {:rows 40 :cols 120}))
    app))

(specification "explorer: sessions list renders against the real resolvers"
  (log/with-merged-config {:min-level :error}
    (let [app    (test-app)
          ;; SessionsReport is the landing route and run-on-mount?, but route explicitly to be sure.
          _      (scr/route-to! app :escapement.ui.screens.sessions-report/SessionsReport {})
          ;; The RAD :keyword formatter renders the name part capitalized — :examples/writer → "Writer".
          screen (wait-for app #"Writer|Hello")]
      (assertions
        "renders the Status + Chart column headings"
        (and (boolean (re-find #"Status" screen)) (boolean (re-find #"Chart" screen))) => true
        "renders both sessions' statuses from the fixture (Done + Incomplete keyword cells)"
        (and (boolean (re-find #"\bDone\b" screen)) (boolean (re-find #"\bIncomplete\b" screen))) => true
        "renders both sessions' chart sources (Writer + Hello)"
        (and (boolean (re-find #"\bWriter\b" screen)) (boolean (re-find #"\bHello\b" screen))) => true
        "renders the started-at epoch value of a fixture session"
        (boolean (re-find #"\b200\b" screen)) => true))))

(specification "explorer: drill-in from a session row renders that session's events"
  (log/with-merged-config {:min-level :error}
    (let [app  (test-app)
          _    (scr/route-to! app :escapement.ui.screens.sessions-report/SessionsReport {})
          _    (wait-for app #"Writer")
          ;; Focus the first session ROW (ids `:row/<idx>`, made focusable by the TUI report plugin),
          ;; then activate it (Enter). The row's first action ("Events") routes to EventsReport for
          ;; that session, passing its id as the ::sc/session-id route param → the EventsReport's
          ;; session-id control → the resolver :params. (Tab-walking the focus ring is order-dependent
          ;; and brittle here, so we focus the row directly — same effect a user gets selecting it.)
          rows (filterv #(and (keyword? %) (= "row" (namespace %)))
                 (mapv :id (engine/focusables (engine/current-node-tree app))))
          _    (engine/focus! app (first rows))]
      (tui-app/step! app {:key :enter})
      ;; The detail report shows no title text; it is identified by its "All Sessions"/"Artifacts"
      ;; nav controls, "Session"/"Kind" columns, and the loaded event rows. Wait for a fixture event.
      (let [screen (wait-for app #"haiku|Request")]
        (assertions
          "drills in to the EventsReport (its session-id control + cross-nav controls render)"
          (and (boolean (re-find #"All Sessions" screen)) (boolean (re-find #"Artifacts" screen))) => true
          "renders the Kind + Seq column headings"
          (and (boolean (re-find #"Kind" screen)) (boolean (re-find #"Seq" screen))) => true
          ;; The RAD :keyword formatter capitalizes the name part — :llm/request → \"Request\", etc.
          "renders the fixture session's event kinds (loaded via the ::sc/session-id route param)"
          (and (boolean (re-find #"\bRequest\b" screen)) (boolean (re-find #"\bDone\b" screen))) => true
          "renders the captured request snippet from the fixture"
          (boolean (re-find #"haiku" screen)) => true)))))

(specification "explorer: artifacts drill-in renders that session's artifacts"
  (log/with-merged-config {:min-level :error}
    (let [app (test-app)
          ;; Route directly to ArtifactsReport for s1 (the session row's second action / detail
          ;; screen). Exercises the ::sc/session-id route param → control → resolver :params path.
          _   (scr/route-to! app :escapement.ui.screens.session-detail/ArtifactsReport
                {::sc/session-id "s1"})
          ;; The path column is width-capped at 18 cells, so \"artifacts/report.md\" renders truncated
          ;; to \"artifacts/report.m\"; match the truncation-safe prefix.
          screen (wait-for app #"artifacts/report|nodes/w/0")]
      (assertions
        "drills in to the ArtifactsReport (its session-id control + cross-nav controls render)"
        (and (boolean (re-find #"Session" screen)) (boolean (re-find #"All Sessions" screen))) => true
        "renders the Path + Class column headings"
        (and (boolean (re-find #"Path" screen)) (boolean (re-find #"Class" screen))) => true
        "renders an artifact path from the fixture (truncated to the column width)"
        (boolean (re-find #"artifacts/report|nodes/w/0" screen)) => true
        ;; The :keyword formatter renders :author → \"Author\", :captured-io → \"Captured-io\".
        "renders the artifact class column value"
        (and (boolean (re-find #"Author" screen)) (boolean (re-find #"Captured-io" screen))) => true))))
