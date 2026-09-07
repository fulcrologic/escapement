(ns escapement.llm.model-quirks
  "A generic seam for the request keys a particular model, on a particular
   endpoint, will not accept.

   Every gateway has a handful of these, they are discovered the hard way (as a
   400 in front of a user), and they are a property of the WIRE — so the
   library is the right place to know them, and no application should have to
   rediscover the same list. `escapement.llm.claude-cli.translate/warn-dropped!`
   has done exactly this for one backend since before this namespace existed;
   this generalises the pattern to a data table any backend can carry.

   --- Endpoint-scoped, not model-scoped -------------------------------------

   The scoping matters and is easy to get wrong. `kimi-k2.7-code` REJECTS any
   temperature other than 1 through opencode.ai's Zen gateway
   (\"invalid temperature: only 1 is allowed for this model\") but ACCEPTS
   temperature 0.7 through Ollama Cloud — same model id, same wire format,
   different answer. Both verified live 2026-09-07. A quirk table keyed by
   model alone would therefore be wrong: it would silently pin a temperature
   the caller asked for on an endpoint that was perfectly happy to honour it.

   Quirks are consequently attached to a BACKEND INSTANCE (`:model-quirks` in
   its opts), which is where the endpoint is known, and matched against the
   model within it.

   --- The table -------------------------------------------------------------

   A quirk is a map:

       {:match #\"^kimi-k2\\\\.7\"      ; regex or exact model string
        :pin   {:temperature 1}      ; keys this model accepts at ONE value only
        :drop  #{:top-k}             ; keys it rejects outright
        :why   \"…\"}                  ; the upstream error, for the log line

   Absent a matching quirk the request is returned untouched, so a backend with
   no quirk table behaves exactly as it did before."
  (:require
    [taoensso.timbre :as log]))

(defn matches?
  "Does `quirk` apply to `model`? `:match` is a regex or an exact string."
  [{:keys [match]} model]
  (let [m (str model)]
    (cond
      (nil? match) false
      (string? match) (= match m)
      :else (boolean (re-find match m)))))

(defn for-model
  "Every quirk in `quirks` that applies to `model`, in table order."
  [quirks model]
  (filterv #(matches? % model) quirks))

(def ^:private warned
  "Warn once per (model, quirk-effect) per process. Every turn of a
   conversation carries the same quirk, and a per-turn line tells the operator
   nothing new after the first."
  (atom #{}))

(defn- warn-once! [model effects why]
  (let [k [model effects]]
    (when-not (contains? @warned k)
      (swap! warned conj k)
      (log/warn "[model-quirks]" (str model)
        "does not accept" (pr-str effects) "on this endpoint —"
        (if why why "adjusted to what the endpoint allows.")
        "This is logged once per process."))))

(defn apply-quirks
  "Return `request` with every quirk in `quirks` that matches its `:model`
   applied: `:drop` keys removed, `:pin` keys forced to their only accepted
   value. Warns once per process per distinct adjustment.

   A `:pin` only fires when the caller actually SET the key — pinning a value
   the caller never asked for would be inventing a request parameter, which is
   not this seam's job."
  [quirks request]
  (let [model (:model request)
        qs    (for-model quirks model)]
    (if (empty? qs)
      request
      (reduce
        (fn [req {:keys [drop pin why]}]
          (let [dropped (filterv #(contains? req %) (or drop #{}))
                pinned  (into {} (filter (fn [[k v]]
                                           (and (contains? req k)
                                             (not= v (get req k))))
                                  (or pin {})))]
            (when (or (seq dropped) (seq pinned))
              (warn-once! model
                (cond-> {}
                  (seq dropped) (assoc :dropped (vec dropped))
                  (seq pinned) (assoc :pinned pinned))
                why))
            (cond-> req
              (seq dropped) (as-> r (apply dissoc r dropped))
              (seq pinned) (merge pinned))))
        request
        qs))))

;;; ---------------------------------------------------------------------------
;;; Known tables, per endpoint

(def opencode-go-quirks
  "opencode.ai Zen gateway. Verified live 2026-09-07."
  [{:match #"^kimi-k2\.7"
    :pin   {:temperature 1}
    :why   "the upstream answers \"invalid temperature: only 1 is allowed for this model\""}])
