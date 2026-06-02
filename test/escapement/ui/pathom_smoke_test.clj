(ns escapement.ui.pathom-smoke-test
  "Permanent guard that the EQL stack — Pathom 2 + transit — keeps running under babashka. Pathom's
   transitive guardrails 0.0.12 uses a timbre macro SCI can't analyze; the explicit guardrails
   1.2.16 pin in deps.edn/bb.edn shadows it. If a dependency bump lets the transitive version win,
   THIS test breaks first (rather than the whole --api-server at runtime). Self-contained: it does
   not depend on escapement.ui.resolvers — it guards the dependencies, not our code."
  (:require
    [cognitect.transit :as transit]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [fulcro-spec.core :refer [=> assertions specification]])
  (:import
    (java.io ByteArrayInputStream ByteArrayOutputStream)))

(pc/defresolver thing-resolver
  "Trivial resolver with an ident input and a nested join, to exercise the connect index + readers."
  [_env {:thing/keys [id]}]
  {::pc/input  #{:thing/id}
   ::pc/output [:thing/label {:thing/child [:child/id :child/name]}]}
  {:thing/label (str "thing-" id)
   :thing/child {:child/id (* id 10) :child/name (str "c" id)}})

(def parser
  (p/parser
    {::p/env     {::p/reader [p/map-reader pc/reader2 pc/ident-reader pc/index-reader]}
     ::p/plugins [(pc/connect-plugin {::pc/register [thing-resolver]})
                  p/error-handler-plugin]}))

(defn transit-roundtrip
  "Round-trip `x` through transit JSON (the --api-server wire format) and back."
  [x]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json) x)
    (transit/read (transit/reader (ByteArrayInputStream. (.toByteArray out)) :json))))

(specification "Pathom + transit run under babashka (dependency-drift guard)"
  (let [result (parser {} [{[:thing/id 3] [:thing/label {:thing/child [:child/name]}]}])
        thing  (get result [:thing/id 3])]
    (assertions
      "a registered resolver resolves an ident input"
      (:thing/label thing) => "thing-3"
      "a nested join resolves through the parser"
      (get-in thing [:thing/child :child/name]) => "c3"
      "transit JSON round-trips the parser result losslessly"
      (transit-roundtrip result) => result)))
