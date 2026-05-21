(ns escapement.invocation.human-input
  "InvocationProcessor for `:type :human-input`.

  On state entry a worker thread is spawned that asks the user a question through
  an injected `HumanRenderer`. When the user answers, the worker posts
  `:on-answer-event` to the parent session with `{:answer <value>}` and dies. On
  cancel it posts `:on-cancel-event`. On a real failure it fires an
  SCXML-style canonical error event:

    :error.human.invalid-answer  — answer failed `:answer-schema`
    :error.human.renderer        — renderer threw / terminal failure
    :error.human.worker-exception — uncaught throwable in the worker

  Chart authors typically use `(transition {:event :error.human.* :target ...})`
  to catch any human-side failure with a single line.

  The processor is renderer-agnostic. Two real renderers ship with escapement:

  * `escapement.tui/->renderer` — pops modals into the bottom region of the
    persistent TUI.
  * `stdin-renderer` (this namespace) — fallback for headless / `--no-tui` runs.
    Reads from `*in*`; uses bb-tui-style ANSI for select / multi-select.

  Params (chart side, via `:params-fn` returning):

    {:kind             :text | :select | :multi-select | :confirm | :progress | :custom
     :prompt           string
     :options          [{:label string :value any}]  ; :select / :multi-select
     :answer-schema    optional Malli schema for the answer
     :on-answer-event  default :human.answer
     :on-cancel-event  default :human.cancelled
     :render           (fn [env data] answer)        ; required for :custom

   Errors fire canonical `:error.human.<reason>` events (see ns docstring);
   chart authors transition on `:error.human.*` rather than configuring an
   `:on-error-event`.}"
  (:require
    [clojure.string :as str]
    [com.fulcrologic.statecharts :as sc]
    [com.fulcrologic.statecharts.environment :as env-ns]
    [com.fulcrologic.statecharts.protocols :as sp]
    [malli.core :as m]
    [malli.error :as me]
    [com.fulcrologic.statecharts.promise :as p]))

;; ---------------------------------------------------------------------------
;; Renderer protocol
;; ---------------------------------------------------------------------------

(defprotocol HumanRenderer
  "All methods take an `opts` map sharing the chart-author params.

   Cross-host async contract: the value-returning methods (`prompt-text`,
   `prompt-select`, `prompt-multi`, `prompt-confirm`, `custom-render`)
   return a `com.fulcrologic.statecharts.promise` promise that resolves to the user's answer or
   rejects (e.g. with `{:reason :cancelled}` for an interrupted modal).
   On CLJ/bb impls may block internally and resolve at the end; on CLJS
   the impl typically returns a real Promise resolved by a click handler.

   Progress methods (`start-progress`, `update-progress`, `end-progress`)
   are pure side-effects and stay synchronous — no value to wait on.

   Cancellation is signalled by the worker via `stop-invocation!`; the
   processor stops awaiting the returned promise and posts the cancel
   event itself. Renderers SHOULD also reject the promise on user-driven
   cancellation so consumers using `p/await!` see the throw."
  (prompt-text [this opts])
  (prompt-select [this opts])
  (prompt-multi [this opts])
  (prompt-confirm [this opts])
  (start-progress [this opts])
  (update-progress [this handle pct label])
  (end-progress [this handle])
  (custom-render [this f env data]))

;; ---------------------------------------------------------------------------
;; Stdin/ANSI fallback renderer (used when no TUI is attached)
;; ---------------------------------------------------------------------------

(defn- read-trim-line []
  (some-> (read-line) str/trim))

(defn- print-prompt! [s]
  (binding [*out* *err*]
    (print s)
    (flush)))

(defn- read-yes-no [default]
  (loop []
    (let [line (or (read-trim-line) "")]
      (cond
        (str/blank? line) default
        (re-matches #"(?i)y(es)?" line) true
        (re-matches #"(?i)no?" line) false
        :else (do (print-prompt! "Please answer y or n: ") (recur))))))

(defn- print-numbered-options! [options]
  (binding [*out* *err*]
    (doseq [[i o] (map-indexed vector options)]
      (println (format "  %d) %s" (inc i) (:label o))))))

(defn- read-index [n]
  (loop []
    (let [line (or (read-trim-line) "")]
      (if-let [i (try (Long/parseLong line) (catch Throwable _ nil))]
        (if (<= 1 i n)
          (dec i)
          (do (print-prompt! (str "Enter 1.." n ": ")) (recur)))
        (do (print-prompt! (str "Enter 1.." n ": ")) (recur))))))

(defn- read-index-set [n]
  (loop []
    (let [line (or (read-trim-line) "")]
      (if (str/blank? line)
        #{}
        (let [parts (->> (str/split line #"[,\s]+")
                      (remove str/blank?))
              idxs  (keep #(try (Long/parseLong %) (catch Throwable _ nil)) parts)]
          (if (and (= (count idxs) (count parts))
                (every? #(<= 1 % n) idxs))
            (set (map dec idxs))
            (do (print-prompt! (str "Enter comma-separated numbers in 1.." n " (blank=none): ")) (recur))))))))

(defrecord StdinRenderer []
  HumanRenderer
  (prompt-text [_ {:keys [prompt]}]
    (p/do!
      (print-prompt! (str (or prompt "?") " "))
      (or (read-trim-line) "")))
  (prompt-select [_ {:keys [prompt options]}]
    (p/do!
      (binding [*out* *err*] (println (or prompt "Select one:")))
      (print-numbered-options! options)
      (print-prompt! "> ")
      (:value (nth options (read-index (count options))))))
  (prompt-multi [_ {:keys [prompt options]}]
    (p/do!
      (binding [*out* *err*] (println (or prompt "Select any (comma-separated, blank=none):")))
      (print-numbered-options! options)
      (print-prompt! "> ")
      (let [idxs (read-index-set (count options))]
        (mapv :value (keep-indexed (fn [i o] (when (idxs i) o)) options)))))
  (prompt-confirm [_ {:keys [prompt default]}]
    (p/do!
      (print-prompt! (str (or prompt "Confirm?") (if default " [Y/n]: " " [y/N]: ")))
      (read-yes-no (boolean default))))
  (start-progress [_ {:keys [prompt]}]
    (binding [*out* *err*] (println (str "[progress] " (or prompt "Working..."))))
    (atom {:pct 0}))
  (update-progress [_ handle pct label]
    (swap! handle assoc :pct pct :label label)
    (binding [*out* *err*]
      (print (format "\r[progress] %3d%% %s" (long (* 100 pct)) (or label "")))
      (flush)))
  (end-progress [_ _]
    (binding [*out* *err*] (println)))
  (custom-render [_ f env data] (p/do! (f env data))))

(defn stdin-renderer [] (->StdinRenderer))

;; ---------------------------------------------------------------------------
;; Worker plumbing (mirrors llm-conversation patterns)
;; ---------------------------------------------------------------------------

(defn- now-ms [] (System/currentTimeMillis))

(defn- transcript! [transcript-fn ev]
  (try (transcript-fn ev) (catch Throwable _ nil)))

(defn- post-event-to-parent!
  [{:keys [env queue parent-session-id invokeid]} event data]
  (sp/send! queue env
    {:target            parent-session-id
     :source-session-id parent-session-id
     :sendid            (str parent-session-id "." invokeid "." (name event))
     :invokeid          invokeid
     :event             event
     :data              data}))

(defn- humanize-malli-errors [schema input]
  (-> (m/explain schema input) me/humanize pr-str))

(defn- dispatch-kind!
  "Render one prompt of the given :kind and return the user's answer.
   Throws on unknown :kind."
  [renderer {:keys [kind render] :as opts} env data]
  (case kind
    :text (prompt-text renderer opts)
    :select (prompt-select renderer opts)
    :multi-select (prompt-multi renderer opts)
    :confirm (prompt-confirm renderer opts)
    :custom (custom-render renderer render env data)
    (throw (ex-info (str "Unsupported :human-input :kind " kind)
             {:kind kind}))))

(defn- error-event
  "SCXML-style canonical error event for the human-input invocation."
  [reason]
  (keyword (str "error.human." (name reason))))

(defn- run-worker!
  [{:keys [renderer worker-state params parent-ctx transcript-fn data]}]
  (let [{:keys [kind on-answer-event on-cancel-event answer-schema]
         :or   {on-answer-event :human.answer
                on-cancel-event :human.cancelled}} params
        env         (:env parent-ctx)
        post-error! (fn [reason data]
                      (post-event-to-parent! parent-ctx (error-event reason)
                        (assoc data :reason reason)))]
    (try
      (transcript! transcript-fn
        {:event :human-input/start
         :ts    (now-ms)
         :data  (cond-> {:kind kind :invokeid (:invokeid parent-ctx)}
                  (:prompt params) (assoc :prompt (:prompt params)))})
      (cond
        (= :dying @worker-state)
        (transcript! transcript-fn {:event :human-input/cancelled :ts (now-ms) :data {}})

        (= :progress kind)
        ;; Progress is fundamentally event-driven; for v1 just end immediately
        ;; and let the chart drive updates by other means. Future: park on a
        ;; queue and consume forwarded :human.progress events.
        (let [handle (start-progress renderer params)]
          (end-progress renderer handle)
          (post-event-to-parent! parent-ctx on-answer-event {:answer :done}))

        :else
        (let [answer (p/await! (dispatch-kind! renderer params env data))]
          (cond
            (= :dying @worker-state)
            (transcript! transcript-fn {:event :human-input/cancelled :ts (now-ms) :data {}})

            (and answer-schema (not (m/validate answer-schema answer)))
            (let [err (humanize-malli-errors answer-schema answer)]
              (transcript! transcript-fn
                {:event :human-input/validation-failed
                 :ts    (now-ms)
                 :data  {:errors err}})
              (post-error! :invalid-answer
                {:errors err :answer answer}))

            :else
            (do
              (transcript! transcript-fn
                {:event :human-input/answer
                 :ts    (now-ms)
                 :data  (cond-> {:kind     kind
                                 :invokeid (:invokeid parent-ctx)}
                          (get params :record-answer? true)
                          (assoc :answer answer))})
              (post-event-to-parent! parent-ctx on-answer-event {:answer answer})))))
      (catch InterruptedException _
        (transcript! transcript-fn {:event :human-input/interrupted :ts (now-ms) :data {}}))
      (catch Throwable t
        (if (= :cancelled (:reason (ex-data t)))
          (do
            (transcript! transcript-fn
              {:event :human-input/cancelled
               :ts    (now-ms)
               :data  {}})
            (try
              (post-event-to-parent! parent-ctx on-cancel-event {})
              (catch Throwable _ nil)))
          (do
            (transcript! transcript-fn
              {:event :human-input/error
               :ts    (now-ms)
               :data  {:message (.getMessage t)}})
            (try
              (post-error! :worker-exception {:message (.getMessage t)})
              (catch Throwable _ nil)))))
      (finally
        (reset! worker-state :dying)))))

(defn- worker-key [parent-session-id invokeid]
  [parent-session-id invokeid])

;; ---------------------------------------------------------------------------
;; Processor
;; ---------------------------------------------------------------------------

(defrecord HumanInputProcessor [renderer transcript-fn workers]
  sp/InvocationProcessor
  (supports-invocation-type? [_ typ]
    (= typ :human-input))

  (start-invocation! [_this env {:keys [invokeid params]}]
    (let [parent-session-id (env-ns/session-id env)
          k                 (worker-key parent-session-id invokeid)
          _                 (when-let [old (get @workers k)]
                              (reset! (:worker-state old) :dying))
          queue             (::sc/event-queue env)
          worker-state      (atom :running)
          parent-ctx        {:env               env :queue queue
                             :parent-session-id parent-session-id
                             :invokeid          invokeid}
          ;; Pull a snapshot of the data model so :custom renderers can use it.
          data              (try
                              (sp/current-data
                                (::sc/data-model env)
                                (assoc env ::sc/context-element-id nil))
                              (catch Throwable _ nil))
          ctx               {:renderer      renderer
                             :worker-state  worker-state
                             :params        params
                             :parent-ctx    parent-ctx
                             :transcript-fn transcript-fn
                             :data          data}
          runnable          (fn [] (run-worker! ctx))
          ^Thread thread    (doto (Thread. ^Runnable runnable
                                    (str "human-input-" parent-session-id "-" invokeid))
                              (.setDaemon true))]
      (swap! workers assoc k {:thread thread :worker-state worker-state})
      (.start thread)
      true))

  (stop-invocation! [_this env {:keys [invokeid]}]
    (let [parent-session-id (env-ns/session-id env)
          k                 (worker-key parent-session-id invokeid)
          entry             (get @workers k)]
      (when entry
        (reset! (:worker-state entry) :dying)
        (try (.interrupt ^Thread (:thread entry)) (catch Throwable _ nil))
        (swap! workers dissoc k))
      true))

  (forward-event! [_this env {:keys [invokeid event]}]
    (let [parent-session-id (env-ns/session-id env)
          k                 (worker-key parent-session-id invokeid)
          entry             (get @workers k)
          ev-name           (cond
                              (keyword? event) event
                              (map? event) (or (:event event) (:name event))
                              :else nil)]
      (when (and entry (= :human.cancel ev-name))
        (reset! (:worker-state entry) :dying)
        (try (.interrupt ^Thread (:thread entry)) (catch Throwable _ nil)))
      true)))

(defn new-processor
  "Create a new `HumanInputProcessor`.

   `opts`:
    * `:renderer` (required) — implementation of `HumanRenderer`
    * `:transcript-fn` (optional) — `(fn [event-map] ...)`; default no-op."
  [{:keys [renderer transcript-fn]}]
  (assert renderer ":renderer is required")
  (->HumanInputProcessor renderer
    (or transcript-fn (fn [_] nil))
    (atom {})))

(defn active-worker-count
  "Number of workers whose state is not :dying. Used by the runner to decide
   when it's safe to terminate."
  [processor]
  (reduce-kv
    (fn [n _ entry]
      (let [s (some-> (:worker-state entry) deref)]
        (if (or (nil? s) (= :dying s)) n (inc n))))
    0
    @(:workers processor)))
