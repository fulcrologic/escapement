(ns escapement.llm.claude-p
  "LLMBackend adapter that shells out to the `claude -p --output-format json` CLI.

   The CLI returns a flat `:result` text field rather than Anthropic-style content blocks
   (see SPIKE_FINDINGS.md). We synthesize a single `{:type :text :text result}` content block.

   ## Sessions / --resume optimization

   The adapter keeps an atom mapping `:conversation/id -> {:session-id ... :messages [...]}`.
   When a new request strictly extends the previously sent messages for that conversation, we
   pass `--resume <session-id>` with only the new tail (typically the last user message). When
   the request is *not* a strict extension, we re-send the full transcript.

   ## Tools

   `claude -p` prompt mode does not expose true Anthropic tool-use. If `:tools` is non-empty
   we WARN once and stringify the tool definitions into the prompt as instructions. Real
   tool-use will require investigation of `--output-format stream-json`."
  (:require
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]
   [com.fulcrologic.guardrails.malli.core :refer [>defn => ?]]
   [escapement.llm.protocol :as proto]
   [escapement.llm.types :as types]))

(>defn ^:private content->text
       "Concatenate all :text blocks in `content` into a single string. Non-text blocks
   are rendered as a marker so the LLM at least sees that something occurred."
       [content]
       [:any => :string]
       (str/join "\n"
                 (for [{:keys [type text] :as blk} content]
                   (case type
                     :text       text
                     :tool_use   (str "[tool_use " (:name blk) " " (pr-str (:input blk)) "]")
                     :tool_result (str "[tool_result" (when (:is-error blk) " ERROR") ": " (:content blk) "]")
                     (pr-str blk)))))

(>defn ^:private message->prompt-chunk
       "Render a single message as a labeled chunk of prompt text."
       [{:keys [role content]}]
       [:any => :string]
       (str (str/upper-case (name role)) ":\n" (content->text content)))

(>defn ^:private build-prompt
       "Build a plain-text prompt for `claude -p` from a Request map.

   In `--resume` mode pass only the new tail messages; otherwise pass everything. Includes
   tool stringification (with WARN at the call site) when tools are present."
       [{:keys [system tools]} messages]
       [:any :any => :string]
       (let [tool-block (when (seq tools)
                          (str "Available tools (JSON):\n"
                               (json/generate-string (vec tools) {:pretty true})
                               "\n\n"))]
         (str
          (when system (str "SYSTEM:\n" system "\n\n"))
          tool-block
          (str/join "\n\n" (mapv message->prompt-chunk messages)))))

(>defn ^:private prefix?
       "Returns true if `prefix` is an element-wise prefix of `coll`."
       [prefix coll]
       [:any :any => :boolean]
       (and (<= (count prefix) (count coll))
            (= (vec prefix) (vec (take (count prefix) coll)))))

(>defn ^:private parse-stop-reason
       "Normalize a `claude -p` stop_reason string to our :stop-reason keyword."
       [s]
       [(? :string) => :keyword]
       (case s
         "end_turn"      :end_turn
         "max_tokens"    :max_tokens
         "tool_use"      :tool_use
         "stop_sequence" :stop_sequence
         :end_turn))

(>defn parse-claude-p-response
       "Pure: parse the JSON map returned by `claude -p --output-format json` into a Response.

   `parsed` is the already-JSON-decoded map (keys as keywords). `request-model` is the model
   the caller asked for (the CLI may use multiple internally; we surface the caller's choice)."
       [parsed request-model]
       [:map :string => :map]
       (let [{:keys [result stop_reason session_id usage model is_error api_error_status]} parsed]
         (when is_error
           (throw (ex-info "claude -p reported an error"
                           {:api-error-status api_error_status
                            :response         parsed})))
         {:stop-reason      (parse-stop-reason stop_reason)
          :content          [{:type :text :text (or result "")}]
          :usage            {:input-tokens                (:input_tokens usage 0)
                             :output-tokens               (:output_tokens usage 0)
                             :cache-creation-input-tokens (:cache_creation_input_tokens usage 0)
                             :cache-read-input-tokens     (:cache_read_input_tokens usage 0)}
          :model            (or model request-model)
          :backend-metadata (cond-> {:backend :claude-p}
                              session_id        (assoc :session-id session_id)
                              (:modelUsage parsed) (assoc :model-usage (:modelUsage parsed)))}))

(>defn ^:private shell-claude-p!
       "Invoke the `claude` binary; returns the parsed JSON map. `args` is the vector of CLI args
   passed after the binary name; `prompt` is appended as the final positional argument
   (matches the spike, which proved this invocation works under bb and JVM)."
       [args prompt]
       [:any :string => :map]
       (let [argv                    (into ["claude"] (concat args [prompt]))
             {:keys [exit out err]}  (apply p/shell
                                            {:out :string :err :string :continue true}
                                            argv)]
         (when-not (zero? exit)
           (throw (ex-info "claude -p exited non-zero"
                           {:exit exit :stderr err :stdout out :args args})))
         (try
           (json/parse-string out true)
           (catch Throwable t
             (throw (ex-info "Failed to parse claude -p JSON output"
                             {:stdout out :cause (.getMessage t)}))))))

(defn- conversation-key [request] (:conversation/id request))

(defrecord ClaudePBackend [state-atom warn-once-atom extra-args]
  proto/LLMBackend
  (send-turn [_ request]
    (when-let [err (types/validate-request request)]
      (throw (ex-info "Invalid LLM request" {:errors err :request request})))
    (when (and (seq (:tools request))
               (compare-and-set! warn-once-atom false true))
      (binding [*out* *err*]
        (println "WARN [escapement.llm.claude-p] claude -p does not expose real tool-use; tool defs will be stringified into the prompt.")))
    (let [model      (:model request)
          conv-key   (conversation-key request)
          prior      (when conv-key (get @state-atom conv-key))
          prior-msgs (:messages prior)
          new-msgs   (:messages request)
          can-resume? (and prior (:session-id prior)
                           (prefix? prior-msgs new-msgs)
                           (> (count new-msgs) (count prior-msgs)))
          tail       (if can-resume? (subvec new-msgs (count prior-msgs)) new-msgs)
          prompt     (build-prompt request tail)
          args       (cond-> ["-p" "--output-format" "json" "--model" model]
                       can-resume? (into ["--resume" (:session-id prior)])
                       :always     (into extra-args))
          parsed     (shell-claude-p! args prompt)
          response   (parse-claude-p-response parsed model)
          new-sid    (get-in response [:backend-metadata :session-id])
          response   (cond-> response can-resume? (assoc-in [:backend-metadata :via] :resume))]
      (when (and conv-key new-sid)
        (swap! state-atom assoc conv-key {:session-id new-sid :messages new-msgs}))
      (when-let [err (types/validate-response response)]
        (throw (ex-info "claude -p produced an invalid response" {:errors err :response response})))
      response)))

(>defn new-backend
       "Construct a new `ClaudePBackend`.

   `opts` may contain:
   - `:extra-args` — vector of extra CLI args appended to every invocation (default `[]`)."
       ([] [=> :any] (new-backend {}))
       ([opts]
        [:map => :any]
        (->ClaudePBackend (atom {}) (atom false) (vec (:extra-args opts)))))
