(ns escapement.tools.web-tools-test
  "Unit tests for `:web/search` and `:web/fetch`. The HTTP layer and the env
   reader are stubbed via `with-redefs` over the in-namespace indirections
   `#'builtin/http-post-json`, `#'builtin/http-get-stream`, and `#'builtin/env`,
   so no network calls (and no host-env dependence) are needed."
  (:require
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [escapement.tools.builtin :as builtin]
   [escapement.tools.protocol :as tp]
   [fulcro-spec.core :refer [specification assertions component =>]])
  (:import
   (java.io ByteArrayInputStream)))

(defn- bytes-stream [^String s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(defn- env-fn [m]
  (fn [k] (get m k)))

(defn- tool-names [tools]
  (into #{} (map tp/tool-name) tools))

;; ---------------------------------------------------------------------------
;; builtin-tools: conditional :web/search registration
;; ---------------------------------------------------------------------------

(specification "builtin-tools registers :web/search only when GEMINI_API_KEY is set"
               (component "without GEMINI_API_KEY: :web/search is absent, :web/fetch is present"
                          (with-redefs [builtin/env (env-fn {})]
                            (let [names (tool-names (builtin/builtin-tools))]
                              (assertions
                               ":web/search absent" (contains? names :web/search) => false
                               ":web/fetch present" (contains? names :web/fetch)  => true
                               "core tools still present"
                               (every? names [:fs/read :fs/write :shell/run :repl/eval]) => true))))

               (component "with GEMINI_API_KEY: :web/search is registered"
                          (with-redefs [builtin/env (env-fn {"GEMINI_API_KEY" "test-key"})]
                            (let [names (tool-names (builtin/builtin-tools))]
                              (assertions
                               ":web/search present" (contains? names :web/search) => true
                               ":web/fetch present"  (contains? names :web/fetch)  => true))))

               (component "new-builtin-registry honors the same env gating"
                          (with-redefs [builtin/env (env-fn {})]
                            (let [reg (builtin/new-builtin-registry)]
                              (assertions
                               "no :web/search in registry"
                               (contains? @reg :web/search) => false
                               ":web/fetch in registry"
                               (contains? @reg :web/fetch) => true)))))

;; ---------------------------------------------------------------------------
;; :web/search
;; ---------------------------------------------------------------------------

(def ^:private sample-gemini-response
  {"candidates"
   [{"content" {"parts" [{"text" "Some summary text."}]}
     "groundingMetadata"
     {"groundingChunks"
      [{"web" {"uri" "https://clojure.org/guides" "title" "Clojure Guides"}}
       {"web" {"uri" "https://example.com/foo"   "title" "Example Foo"}}
       {"web" {"uri" "https://example.com/bar"   "title" "Example Bar"}}]
      "groundingSupports"
      [{"segment" {"text" "Clojure is great for data."}
        "groundingChunkIndices" [0]}
       {"segment" {"text" "Foo bar baz."}
        "groundingChunkIndices" [1 2]}]}}]})

(specification ":web/search"
               ;; Build the registry with env stubbed so :web/search is present
               ;; regardless of whether the test host has GEMINI_API_KEY in env.
               (let [reg (with-redefs [builtin/env (env-fn {"GEMINI_API_KEY" "test-key"})]
                           (builtin/new-builtin-registry))]
                 (component "missing GEMINI_API_KEY is reported as a non-throwing error"
                            (with-redefs [builtin/env             (env-fn {})
                                          builtin/http-post-json  (fn [& _] (throw (ex-info "should not be called" {})))]
                              (let [r (tp/dispatch reg :web/search {:query "anything"})]
                                (assertions
                                 "is-error true"            (:is-error r) => true
                                 "result mentions the key"  (boolean (re-find #"GEMINI_API_KEY" (:result r))) => true))))

                 (component "schema validation rejects malformed input"
                            (let [bad (tp/dispatch reg :web/search {:query "ok" :max-results 99})]
                              (assertions
                               "is-error"          (:is-error bad) => true
                               "mentions validation"
                               (boolean (re-find #"validation" (:result bad))) => true)))

                 (component "happy path parses groundingMetadata into title/url/snippet"
                            (with-redefs [builtin/env             (env-fn {"GEMINI_API_KEY" "test-key"})
                                          builtin/http-post-json  (fn [_url _body _to]
                                                                    {:status 200
                                                                     :body   (json/generate-string sample-gemini-response)})]
                              (let [r      (tp/dispatch reg :web/search {:query "clj" :max-results 3})
                                    parsed (edn/read-string (:result r))]
                                (assertions
                                 "non-error"      (:is-error r) => false
                                 "three results"  (count parsed) => 3
                                 "first url"      (:url (first parsed))      => "https://clojure.org/guides"
                                 "first title"    (:title (first parsed))    => "Clojure Guides"
                                 "first snippet"  (:snippet (first parsed))  => "Clojure is great for data."
                                 "second snippet" (:snippet (second parsed)) => "Foo bar baz."))))

                 (component "max-results caps the returned vector"
                            (with-redefs [builtin/env             (env-fn {"GEMINI_API_KEY" "test-key"})
                                          builtin/http-post-json  (fn [_ _ _]
                                                                    {:status 200
                                                                     :body   (json/generate-string sample-gemini-response)})]
                              (let [r      (tp/dispatch reg :web/search {:query "clj" :max-results 2})
                                    parsed (edn/read-string (:result r))]
                                (assertions
                                 "non-error"     (:is-error r) => false
                                 "two results"   (count parsed) => 2))))

                 (component "non-2xx Gemini response is reported as an error"
                            (with-redefs [builtin/env             (env-fn {"GEMINI_API_KEY" "test-key"})
                                          builtin/http-post-json  (fn [_ _ _] {:status 500 :body "boom"})]
                              (let [r (tp/dispatch reg :web/search {:query "x"})]
                                (assertions
                                 "is-error"      (:is-error r) => true
                                 "mentions HTTP" (boolean (re-find #"HTTP 500" (:result r))) => true))))))

;; ---------------------------------------------------------------------------
;; :web/fetch
;; ---------------------------------------------------------------------------

(specification ":web/fetch"
               (let [reg (builtin/new-builtin-registry)]

                 (component "happy path: writes file, returns metadata + preview + title"
                            (let [html (str "<!doctype html><html><head><title>  Hello World  </title></head>"
                                            "<body><p>Body content here.</p></body></html>")]
                              (with-redefs [builtin/http-get-stream
                                            (fn [url _to]
                                              {:status  200
                                               :headers {"content-type" "text/html; charset=utf-8"}
                                               :uri     (java.net.URI. url)
                                               :body    (bytes-stream html)})]
                                (let [r      (tp/dispatch reg :web/fetch {:url "https://example.com/p"})
                                      parsed (edn/read-string (:result r))]
                                  (assertions
                                   "non-error"            (:is-error r) => false
                                   "status 200"           (:status parsed) => 200
                                   "url echoed"           (:url parsed) => "https://example.com/p"
                                   "content-type"         (:content-type parsed) => "text/html; charset=utf-8"
                                   "bytes match payload"  (:bytes parsed) => (count (.getBytes html "UTF-8"))
                                   "saved file exists"    (.exists (io/file (:saved-to parsed))) => true
                                   "saved-to under tmp/escapement-fetch"
                                   (boolean (str/includes? (:saved-to parsed) "escapement-fetch")) => true
                                   "title extracted"      (:title parsed) => "Hello World"
                                   "preview includes body content"
                                   (boolean (str/includes? (:preview parsed) "Body content here.")) => true
                                   "not truncated"        (:truncated parsed) => false)))))

                 (component "truncation at max-bytes is reported, no error"
                            (let [payload (apply str (repeat 8192 \a))]      ;; 8192 bytes
                              (with-redefs [builtin/http-get-stream
                                            (fn [_url _to]
                                              {:status  200
                                               :headers {"content-type" "text/plain"}
                                               :body    (bytes-stream payload)})]
                                (let [r      (tp/dispatch reg :web/fetch {:url "https://x" :max-bytes 1024})
                                      parsed (edn/read-string (:result r))]
                                  (assertions
                                   "non-error"     (:is-error r) => false
                                   "truncated"     (:truncated parsed) => true
                                   "bytes capped"  (:bytes parsed) => 1024)))))

                 (component "non-2xx status is an error"
                            (with-redefs [builtin/http-get-stream
                                          (fn [_url _to]
                                            {:status  404
                                             :headers {"content-type" "text/plain"}
                                             :body    (bytes-stream "nope")})]
                              (let [r (tp/dispatch reg :web/fetch {:url "https://x/missing"})]
                                (assertions
                                 "is-error"       (:is-error r) => true
                                 "mentions 404"   (boolean (re-find #"404" (:result r))) => true))))

                 (component "network exception is an error"
                            (with-redefs [builtin/http-get-stream
                                          (fn [_url _to] (throw (java.net.ConnectException. "refused")))]
                              (let [r (tp/dispatch reg :web/fetch {:url "https://nope"})]
                                (assertions
                                 "is-error"      (:is-error r) => true
                                 "mentions failure" (boolean (re-find #"web/fetch failed" (:result r))) => true))))

                 (component "schema rejects out-of-range max-bytes"
                            (let [r (tp/dispatch reg :web/fetch {:url "https://x" :max-bytes 100})]
                              (assertions
                               "is-error"        (:is-error r) => true
                               "validation msg"  (boolean (re-find #"validation" (:result r))) => true)))))
