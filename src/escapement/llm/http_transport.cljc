(ns escapement.llm.http-transport
  "HTTP transport abstraction for LLM backends.

   Each LLM backend (`escapement.llm.api`, `escapement.llm.openai`,
   `escapement.llm.openai-codex.http`) used to inline `babashka.http-client`
   plus its own SSE-line-reading loop. They now go through this protocol so
   the host picks how HTTP gets done. The CLJ default impl wraps
   `babashka.http-client` (works on bb and JVM). A future CLJS impl would
   wrap `js/fetch` + `ReadableStream`.

   ## Protocol

   `(request transport req)` returns a promise of
   `{:status int :headers map :body string}`.

   `(request-streaming transport req on-line)` returns a promise of
   `{:status int :headers map}`. For 2xx responses, `(on-line line)` is
   called for each line of the response body as it arrives; the resolved
   value carries no body. For non-2xx, no `on-line` calls happen and the
   resolved value carries `:body` populated with the full error body.

   `req` shape:
     {:url        string
      :method     :get | :post
      :headers    map<string, string>
      :body       string | nil
      :timeout-ms int | nil}

   ## Why lines, not SSE events

   The three providers all stream via SSE but parse it slightly differently
   (Anthropic uses both `event:` and `data:` lines and dispatches by event;
   OpenAI sends `data:` only and parses the JSON; ChatGPT Codex has its own
   event/done flavor). Lifting line-streaming into the transport keeps the
   provider-specific SSE interpretation in the backends.

   ## Cancellation

   Not in the contract. The owning InvocationProcessor stops listening to
   the returned promise when the chart leaves the invoking state; the
   transport may still complete its work. Callers must tolerate this."
  (:require
    [com.fulcrologic.statecharts.promise :as p]
    #?@(:clj [[babashka.http-client :as http]])
    [clojure.string :as str])
  #?(:clj
     (:import
       (java.io BufferedReader InputStreamReader))))

(defprotocol HttpTransport
  (request [this req]
    "Perform a non-streaming HTTP request. Returns a promise of
     {:status int :headers map :body string}.")
  (request-streaming [this req on-line]
    "Perform a streaming HTTP request. For 2xx responses, calls
     `(on-line line)` for each line of the body as it arrives. Returns a
     promise of {:status int :headers map} for 2xx, or
     {:status int :headers map :body string} for non-2xx (no on-line calls
     made). `on-line` exceptions are caught and logged but do not abort
     the stream."))

;; ---------------------------------------------------------------------------
;; CLJ / bb default: babashka.http-client backed transport
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- http-request-opts [{:keys [method headers body timeout-ms]}]
     (cond-> {:headers (or headers {})
              :throw   false}
       body       (assoc :body body)
       timeout-ms (assoc :timeout timeout-ms))))

#?(:clj
   (defn- do-request [{:keys [url method] :as req}]
     (let [opts (http-request-opts req)]
       (case (or method :get)
         :get  (http/get  url opts)
         :post (http/post url opts)))))

#?(:clj
   (defn- do-stream-request
     "Run `req` with :as :stream, then either drain the body fully (non-2xx)
      or invoke (on-line line) per response body line (2xx). Returns a map
      shaped per the protocol."
     [{:keys [url method] :as req} on-line]
     (let [opts                  (assoc (http-request-opts req) :as :stream)
           {:keys [status headers body]}
                                 (case (or method :get)
                                   :get  (http/get  url opts)
                                   :post (http/post url opts))]
       (if (and (>= status 200) (< status 300))
         (do
           (with-open [reader (BufferedReader. (InputStreamReader. body "UTF-8"))]
             (loop []
               (let [line (.readLine reader)]
                 (when (some? line)
                   (try (on-line line)
                        (catch Throwable e
                          (binding [*out* *err*]
                            (println "[http-transport] on-line threw:"
                              (ex-message e)))))
                   (recur)))))
           {:status status :headers headers})
         {:status  status
          :headers headers
          :body    (try (slurp body) (catch Throwable _ ""))}))))

#?(:clj
   (defrecord BabashkaHttpTransport []
     HttpTransport
     (request [_ req]
       ;; bb's http-client is blocking; wrap in a future so callers always
       ;; consume the result through a promise.
       (let [d (promise)]
         (future
           (try (deliver d (do-request req))
                (catch Throwable e
                  (deliver d (p/rejected e)))))
         (p/then d identity)))
     (request-streaming [_ req on-line]
       (let [d (promise)]
         (future
           (try (deliver d (do-stream-request req on-line))
                (catch Throwable e
                  (deliver d (p/rejected e)))))
         (p/then d identity)))))

#?(:clj
   (def ^:private default-impl (delay (->BabashkaHttpTransport))))

(defn default-transport
  "Returns the default HttpTransport implementation for the current host.

   - CLJ/bb: a `BabashkaHttpTransport` backed by `babashka.http-client`.
   - CLJS: nil. CLJS hosts must construct their own transport (e.g. one
     backed by `js/fetch`) and supply it to backends via the
     `:http-transport` opts entry."
  []
  #?(:clj  @default-impl
     :cljs nil))
