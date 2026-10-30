(ns escapement.recording.replay
  "Replay a recorded run (`escapement.recording`) against a chart — the same version
   or another — and report where it diverged. This is how real runs become eval
   cases.

   The replay starts the chart with the recorded initial data on a test clock set
   to the recording's start, and delivers the recorded outside events in their
   recorded order, each once the chart has settled after the previous one (timers
   due before an event, by its recorded offset, fire first). AI steps are answered:
     * `:ai :recorded` (default) — by the recording's own answers, matched by step and
       then by visit and turn in order;
     * `:ai :scripted` with `:script {…}` — by a script (`escapement.llm.scripted`);
     * `:backend b` — by any backend, a real one included.
   Tools are only those the replay is given (`:tool-registry`, plus pretend `:tools`),
   so outside actions can be pretend ones. A replay writes its own transcript in its
   own directory and never touches the recorded run's files.

       (replay! recording {:chart c :chart-id id :tool-registry pretend})
       ;; => run-case's result map, plus
       ;;    :diverged? bool
       ;;    :divergences [{:kind :ai-unanswered :step \"classify\" :turn 1 …}
       ;;                  {:kind :ai-unused :step \"draft\" :replies 1}
       ;;                  {:kind :ended-elsewhere :recorded [:done] :replayed [:review]}
       ;;                  {:kind :status :recorded :done :replayed :waiting}]"
  (:require
    [escapement.llm.scripted :as scripted]
    [escapement.testing.cases :as cases]))

(defn as-case
  "The test case that replays `recording` (no expectations)."
  [recording]
  {:name         (str "replay of " (or (:run-id recording) (:session-id recording) "a recorded run"))
   :start        (:started-at recording)
   :initial-data (:initial-data recording)
   :events       (:events recording)
   :ai           (:ai recording)})

(defn- divergences [recording result backend recorded-ai?]
  (vec
    (concat
      (for [{:keys [step turn reason] :as d} (or (try (scripted/divergences backend) (catch Exception _ nil)) [])]
        (merge {:kind (case reason :exhausted :ai-unanswered :ai-not-offered) :step step :turn turn}
          (select-keys d [:tool :what])))
      (when recorded-ai?
        (for [[step left] (or (try (scripted/remaining backend) (catch Exception _ nil)) {})]
          {:kind :ai-unused :step step :replies (count left)}))
      (when (and (seq (:final recording)) (not= (set (:final recording)) (set (:final result))))
        [{:kind :ended-elsewhere :recorded (:final recording) :replayed (:final result)}])
      (when (and (:status recording) (not= (:status recording) (:status result))
              ;; a recorded run that finished reports :done; so does a replay
              (not (and (= :done (:status recording)) (= :done (:status result)))))
        [{:kind :status :recorded (:status recording) :replayed (:status result)}]))))

(defn replay!
  "Replay `recording` against `{:chart :chart-id}` (see the ns doc). `opts`:
   `:chart` (required), `:chart-id`, `:shape`, `:ai` (`:recorded` | `:scripted`),
   `:script`, `:backend`, `:tool-registry`, `:tools` (pretend tool specs),
   `:work-dir`, `:expect` (run-case expectations, optional)."
  [recording {:keys [chart chart-id shape ai script backend tool-registry tools work-dir expect]
              :or   {ai :recorded}
              :as   opts}]
  (assert chart "replay! needs :chart")
  (let [recorded? (and (nil? backend) (= ai :recorded))
        backend   (or backend
                    (scripted/backend {:script (if recorded? (:ai recording) script)}))
        tc        (cond-> (as-case recording)
                    tools (assoc :tools tools)
                    expect (assoc :expect expect))
        result    (cases/run-case {:chart chart :chart-id chart-id :shape shape} tc
                    (cond-> {:backend backend}
                      tool-registry (assoc :tool-registry tool-registry)
                      work-dir (assoc :work-dir work-dir)))
        ds        (divergences recording result backend recorded?)]
    (assoc result :diverged? (boolean (seq ds)) :divergences ds :recording/chart-id (:chart-id recording))))
