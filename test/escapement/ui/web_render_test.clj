(ns escapement.ui.web-render-test
  "Headless DOM render tests for the Semantic-UI browser explorer — NO browser.

   The SAME statechart-driven RAD screens the browser SPA mounts (`escapement.ui.client`) are wired
   here to `escapement.ui.resolvers/process` through an in-process loopback remote and rendered to an
   HTML hiccup tree under CLJ (`com.fulcrologic.fulcro.dom-server` via the Fulcro headless
   frame-capturing render). Because the components are CLJC and emit `dom-server` markup with
   classNames, their STRUCTURE — Semantic-UI report classes, column headings, fixture cell values,
   the Debugger control panel — renders deterministically off-browser. Pixel CSS and the ELK chart
   visualizer's actual SVG layout stay browser-only (DOM measurement) and are NOT asserted here.

   App build mirrors `escapement.ui.client`: `rad-app/fulcro-rad-app`'s install sequence
   (`install-ui-controls!` + the Semantic-UI plugin, `install-statecharts! :event-loop? true`,
   `start-routing!`), but on a `headless/build-test-app` so we get the sync-tx + frame-capturing
   render the SPA gets from the browser. Loads are async (the statechart event loop drains the
   report load → process → ready follow-on), so we POLL the rendered hiccup until data appears.

   JVM-only (RAD render stack); excluded from `bb test` (see runner's jvm-only-namespaces) and run
   via `clojure -M:ui-test`."
  (:require
    [clojure.string :as str]
    [clojure.walk :as walk]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.dom-server :as dom]
    [com.fulcrologic.fulcro.headless :as h]
    [com.fulcrologic.fulcro.headless.loopback-remotes :as lb]
    [com.fulcrologic.guardrails.config :as grc]
    [com.fulcrologic.rad.statechart.application :as rad-app]
    [com.fulcrologic.statecharts :as-alias sc]
    [com.fulcrologic.statecharts.integration.fulcro.routing :as scr]
    [com.wsscode.pathom.core :as p]
    [escapement.protocols :as proto]
    [escapement.ui.rendering.semantic-ui.plugin :as sui]
    [escapement.ui.resolvers :as r]
    [escapement.ui.screens.chart-view :refer [ChartView]]
    [escapement.ui.screens.debugger :refer [Debugger]]
    [escapement.ui.screens.root :as root]
    [escapement.ui.screens.routing :refer [routing-chart]]
    [escapement.ui.screens.sessions-report :refer [SessionsReport]]
    [fulcro-spec.core :refer [=> assertions component specification]]
    [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Guardrails exclusions. The `:ui-test` alias runs guardrails in `:runtime` mode; the RAD report /
;; report-render fns are heavily `>defn`-instrumented over large maps, which makes each render
;; pathologically slow (the polling loop then looks like a hang). Excluding the hot RAD render
;; namespaces drops a render to milliseconds while leaving guardrails on for everything else.
;; ---------------------------------------------------------------------------

(defonce ^:private render-checks-excluded
  (do
    (grc/exclude-checks! 'com.fulcrologic.rad.statechart.report)
    (grc/exclude-checks! 'com.fulcrologic.rad.report-render)
    (grc/exclude-checks! 'com.fulcrologic.rad.statechart.control)
    true))

;; ---------------------------------------------------------------------------
;; In-memory fixture store (reused shape from resolvers-test / tui_render_test). Two sessions; s1
;; carries a 3-event transcript (runner/started, llm/request w/ a captured snippet, runner/done) and
;; two artifacts (an author report + a captured-IO request).
;; ---------------------------------------------------------------------------

(defn- stub-store
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
;; Loopback remote. A real http-remote ships transit, which ELIDES Pathom's `::p/not-found` sentinel
;; values (they never reach the client). An in-process loopback would otherwise hand the raw
;; not-found keyword to the report renderer (a `:string` column receiving the not-found keyword
;; aborts the row render). Scrubbing not-found here reproduces the on-the-wire view faithfully.
;; ---------------------------------------------------------------------------

(defn- scrub-not-found
  [response]
  (walk/postwalk
    (fn [v] (if (map? v)
              (into {} (remove (fn [[_ vv]] (= vv ::p/not-found))) v)
              v))
    response))

(defn- test-app
  "Builds a headless explorer app whose remote calls the Escapement read resolvers in-process (via
   `r/process` over the fixture store), installs the Semantic-UI RAD render plugin + statecharts +
   routing, and mounts `Root` — exactly the install sequence `escapement.ui.client/init` runs."
  []
  render-checks-excluded
  (let [store    (stub-store fixture)
        ctx      {:escapement/store store :escapement/active-session-id "s1"}
        the-app  (h/build-test-app
                   {:root-class root/Root
                    :remotes    {:remote (lb/sync-remote (fn [eql] (scrub-not-found (r/process ctx eql))))}})]
    (rad-app/install-ui-controls! the-app sui/all-controls)
    (rad-app/install-statecharts! the-app {:event-loop? true})
    (rad-app/start-routing! the-app routing-chart)
    (app/mount! the-app root/Root :app)
    the-app))

(defn- html-when
  "Renders `app` repeatedly (the statechart event loop drains report loads across frames) and returns
   the rendered hiccup as a string once it matches `re`, polling up to ~6s. Returns the last string
   on timeout so the assertion fails against the real screen rather than hanging."
  [app re]
  (loop [n 0]
    (let [hiccup (h/hiccup-frame app)
          s      (when hiccup (pr-str hiccup))]
      (if (or (and s (re-find re s)) (>= n 60))
        (or s "")
        (do (h/render-frame! app) (Thread/sleep 80) (recur (inc n)))))))

;; ===========================================================================
;; Semantic-UI report rendering (SessionsReport + drill-in screens)
;; ===========================================================================

(specification "explorer SessionsReport renders Semantic-UI report structure + fixture rows"
  (log/with-merged-config {:min-level :error}
    (let [app (test-app)
          _   (scr/route-to! app SessionsReport {})
          ;; The RAD :keyword formatter capitalizes the name part — :examples/writer → "Writer".
          html (html-when app #"Writer|Hello")]
      (assertions
        "emits the Semantic-UI report body table classes from report.cljc render-body"
        (str/includes? html "ui selectable celled attached table") => true
        "emits the Semantic-UI control-bar segment + buttons classes from render-report-controls"
        (and (str/includes? html "ui top attached compact segment")
          (str/includes? html "ui right floated buttons")) => true
        "renders the RAD container + header classes"
        (and (str/includes? html "ui container") (str/includes? html "ui header")) => true
        "renders the Status + Chart column headings"
        (and (str/includes? html "Status") (str/includes? html "Chart")) => true
        "renders both fixture sessions' statuses (Done + Incomplete keyword cells)"
        (and (str/includes? html "Done") (str/includes? html "Incomplete")) => true
        "renders both fixture sessions' chart sources (Writer + Hello)"
        (and (str/includes? html "Writer") (str/includes? html "Hello")) => true
        "renders a fixture session's started-at epoch cell value"
        (str/includes? html "200") => true
        "renders the row-action buttons (Events drill-in) as tiny SUI buttons"
        (and (str/includes? html "Events") (str/includes? html "ui tiny button")) => true))))

(specification "explorer EventsReport drill-in renders that session's transcript"
  (log/with-merged-config {:min-level :error}
    (let [app (test-app)
          ;; Route directly to the EventsReport for s1 with the ::sc/session-id route param (the same
          ;; param a SessionsReport row action supplies). That param becomes the report's session-id
          ;; control, then the resolver :params.
          _   (scr/route-to! app :escapement.ui.screens.session-detail/EventsReport {::sc/session-id "s1"})
          html (html-when app #"haiku")]
      (assertions
        "renders the Semantic-UI report table for the events"
        (str/includes? html "ui selectable celled attached table") => true
        "renders the cross-nav controls (All Sessions + Artifacts + Session id control)"
        (and (str/includes? html "All Sessions") (str/includes? html "Artifacts")
          (str/includes? html "Session")) => true
        "renders the Kind + Seq column headings"
        (and (str/includes? html "Kind") (str/includes? html "Seq")) => true
        ;; :llm/request → \"Request\", :runner/done → \"Done\" via the :keyword formatter.
        "renders the fixture session's event kinds (loaded via the ::sc/session-id param)"
        (and (str/includes? html "Request") (str/includes? html "Done")) => true
        "renders the captured llm/request snippet from the fixture"
        (str/includes? html "haiku") => true))))

(specification "explorer ArtifactsReport drill-in renders that session's artifacts"
  (log/with-merged-config {:min-level :error}
    (let [app (test-app)
          _   (scr/route-to! app :escapement.ui.screens.session-detail/ArtifactsReport {::sc/session-id "s1"})
          html (html-when app #"artifacts/report|nodes/w/0")]
      (assertions
        "renders the Semantic-UI report table for the artifacts"
        (str/includes? html "ui selectable celled attached table") => true
        "renders the Path + Class column headings"
        (and (str/includes? html "Path") (str/includes? html "Class")) => true
        "renders fixture artifact paths (author report + captured-IO node path)"
        (and (str/includes? html "artifacts/report.md") (str/includes? html "nodes/w/0")) => true
        ;; :author → \"Author\", :captured-io → \"Captured-io\" via the :keyword formatter.
        "renders the artifact class column values"
        (and (str/includes? html "Author") (str/includes? html "Captured-io")) => true
        "renders the cross-nav controls (All Sessions + Events)"
        (and (str/includes? html "All Sessions") (str/includes? html "Events")) => true))))

;; ===========================================================================
;; Debugger structure (seed the shared live entity directly — no live server)
;; ===========================================================================

(defn- render-from-ident
  "Render component `cls` to an HTML string from a normalized `state` map, denormalizing the props
   from the component's `ident`. Used to render a single screen in isolation without mounting a full
   routed app (the screen reads its data via a link query into `state`)."
  [app cls state ident]
  (let [props (fdn/db->tree (comp/get-query cls state) (get-in state ident) state)]
    (binding [comp/*app* app]
      (dom/render-to-str ((comp/factory cls) props)))))

(specification "Debugger renders the live control panel from a seeded snapshot"
  (log/with-merged-config {:min-level :error}
    (let [app   (test-app)
          ;; Seed the shared live entity `[:component/id :escapement.ui.control/live]` directly to a
          ;; known snapshot — the Debugger reads it via a link query, so no live server is needed.
          state {:component/id
                 {:escapement.ui.control/live
                  {:session/paused?            true
                   :session/step-budget        1
                   :session/live-configuration #{:some/state}
                   :session/pending-events     [{:event/name :x :event/target "s" :event/external? true}]}
                  :escapement.ui.screens.debugger/Debugger {}}}
          html  (render-from-ident app Debugger state [:component/id :escapement.ui.screens.debugger/Debugger])]
      (assertions
        "renders the four control buttons (Pause / Step / Continue / Arm)"
        (and (str/includes? html "Pause") (str/includes? html "Step")
          (str/includes? html "Continue") (str/includes? html "Arm")) => true
        "renders the Semantic-UI button + segment classes"
        (and (str/includes? html "ui button") (str/includes? html "ui segment")) => true
        "renders the paused status from the seeded snapshot"
        (str/includes? html "Paused") => true
        "renders the seeded active configuration state id"
        (and (str/includes? html "Active configuration") (str/includes? html ":some/state")) => true
        "renders the seeded pending event in the pending-events table"
        (and (str/includes? html "Pending events") (str/includes? html ":x")) => true
        "renders the pending-events table with its Semantic-UI table class"
        (str/includes? html "ui celled compact table") => true))))

;; ===========================================================================
;; ChartView data-wiring (query contract). The visualizer itself is browser-only.
;; ===========================================================================

(defn- query-join-subquery
  "Returns the subquery of the `join-key` join within EQL query `q`, or nil."
  [q join-key]
  (some #(when (and (map? %) (contains? % join-key)) (get % join-key)) q))

(specification "ChartView query wires the chart-def join + the shared live-config link"
  (let [q (comp/get-query ChartView)]
    (assertions
      "queries the chart definition via the :ui/chart-def join (the rendered chart's data source)"
      (query-join-subquery q :ui/chart-def) => [:chart/definition]
      "reads the active configuration from the SHARED live entity link (so a Debugger step highlights here)"
      (query-join-subquery q [:component/id :escapement.ui.control/live])
      => [:session/live-configuration]
      "includes the visualizer placeholder join (CLJS-only subquery; nil under headless CLJ)"
      (contains? (set (keys (into {} (filter map?) q))) :ui/visualizer) => true)))

;; NOTE — UNTESTABLE (browser-only DOM measurement): ChartView's actual visualizer render is excluded
;; from headless coverage. The visualizer (`com.fulcrologic.statecharts.visualization.visualizer`) is
;; a CLJS-only component; ChartView's `:ui/visualizer` query join therefore has a `nil` subquery under
;; CLJ, so the component cannot be denormalized/rendered through the factory off-browser. Its
;; mount-load lifecycle (which `df/load!`s `:escapement/chart` + the live config) likewise only fires
;; in a real mount. The ELK layout it draws is pure browser DOM measurement. The data CONTRACT that
;; drives it (the chart-def join + shared live-config link, asserted above) is what is testable
;; headlessly, and is covered.
