(ns escapement.llm.auth
  "Host-owned HTTP authentication. No environment, credential store, or login UI."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.statecharts.promise :as p]
    [escapement.llm.http-transport :as ht]
    [escapement.llm.protocol :as proto]))

(defn- authenticated-request
  [auth-fn req refresh?]
  (let [result (try (auth-fn {:refresh? refresh?})
                   (catch Throwable _
                     ;; Host exceptions can contain tokens. Do not retain their data/cause.
                     (throw (proto/llm-error :auth "Host authentication callback failed"))))
        headers (:headers result)]
    (when-not (and (map? headers) (seq headers)
               (every? (fn [[k v]] (and (string? k) (string? v) (not (str/blank? v)))) headers))
      (throw (proto/llm-error :auth "Host :auth-fn must return {:headers {string nonblank-string}}")))
    (let [replaced (into #{"authorization" "x-api-key"} (map str/lower-case (keys headers)))]
      (update req :headers
        #(merge (into {} (remove (fn [[k _]] (contains? replaced (str/lower-case k)))) %) headers)))))

(defn- send-authenticated
  "One attempt with host headers; on HTTP 401 refresh once and retry once.

   `sink-used?` is nil for a plain request and, on the streaming path, a
   0-arg predicate reporting whether the failed attempt already handed lines
   to the caller's sink. `HttpTransport` promises a non-2xx response delivers
   no lines, so this is only reachable when a host-supplied transport breaks
   that contract — and retrying then would fold two attempts' lines into one
   accumulator, producing duplicated deltas or a stale terminal event. Fail
   with the 401's own category instead of silently corrupting the turn."
  [auth-fn req send! sink-used?]
  (p/do!
    (let [response (p/await! (send! (authenticated-request auth-fn req false)))]
      (cond
        (not= 401 (:status response)) response

        (and sink-used? (sink-used?))
        (throw (proto/llm-error :auth
                 "Host transport delivered response lines before reporting HTTP 401; refusing to retry into a partially consumed stream"))

        :else (p/await! (send! (authenticated-request auth-fn req true)))))))

(defn transport
  "Resolve :http-transport, wrapping it when :auth-fn is supplied.
   The synchronous callback receives {:refresh? boolean} and returns
   {:headers {string string}}. Called before each request; once more on 401
   with refresh? true. The host owns expiry checks, refresh, persistence and
   concurrent refresh coordination. Callback headers replace static auth.

   On the streaming path the retry is skipped — and the 401 raised as an
   `:auth` failure — when the failed attempt already delivered lines to the
   sink, since replaying into a half-filled accumulator corrupts the turn.
   A transport honouring the `HttpTransport` contract never reaches that."
  [{:keys [auth-fn http-transport]}]
  (let [inner (or http-transport (ht/default-transport))]
    (if-not auth-fn
      inner
      (reify ht/HttpTransport
        (request [_ req]
          (send-authenticated auth-fn req #(ht/request inner %) nil))
        (request-streaming [_ req on-line]
          (let [used? (atom false)
                sink  (fn [line] (reset! used? true) (on-line line))]
            (send-authenticated auth-fn req
              #(ht/request-streaming inner % sink)
              #(deref used?))))))))
