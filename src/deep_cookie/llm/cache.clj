(ns deep-cookie.llm.cache
  "Content-addressed disk cache wrapping any LLMBackend.

   Key = SHA-256 over canonicalized (`:model`, `:system`, `:messages`, `:tools`). On hit
   we return the stored response without invoking the inner backend; on miss we delegate
   to the inner backend, store the response on disk atomically, and return it.

   Opt-in via the `DEEP_COOKIE_LLM_CACHE=1` env var or by explicitly constructing
   `caching-backend` in code."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [com.fulcrologic.guardrails.malli.core :refer [>defn =>]]
   [deep-cookie.llm.protocol :as proto])
  (:import
   (java.nio.file CopyOption Files StandardCopyOption)
   (java.security MessageDigest)))

(>defn ^:private canonical-key
       "Return the canonicalized subset of `request` used for cache keying."
       [request]
       [:map => :map]
       {:model    (:model request)
        :system   (:system request)
        :messages (:messages request)
        :tools    (:tools request)})

(defn- sha256-hex [^String s]
  (let [md (MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) bs))))

(>defn cache-key
       "Return a hex-encoded SHA-256 digest of the canonicalized request."
       [request]
       [:map => :string]
       (sha256-hex (pr-str (canonical-key request))))

(defn- ensure-dir! [^String dir]
  (let [d (io/file dir)] (when-not (.exists d) (.mkdirs d))))

(defn- atomic-write-edn! [file value]
  (ensure-dir! (.getParent (io/file file)))
  (let [tmp (io/file (str (.getPath ^java.io.File file) ".tmp"))]
    (with-open [w (io/writer tmp)]
      (binding [*out* w] (pr value)))
    (Files/move (.toPath tmp) (.toPath (io/file file))
                (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                        StandardCopyOption/REPLACE_EXISTING]))
    nil))

(defn- read-edn-file [file]
  (when (.exists (io/file file))
    (edn/read-string {:default tagged-literal} (slurp file))))

(defrecord CachingBackend [inner dir]
  proto/LLMBackend
  (send-turn [_ request]
    (let [k    (cache-key request)
          file (io/file dir (str k ".edn"))]
      (if-let [hit (read-edn-file file)]
        hit
        (let [response (proto/send-turn inner request)]
          (atomic-write-edn! file response)
          response)))))

(>defn caching-backend
       "Wrap `inner` (any `LLMBackend`) with an on-disk replay cache rooted at `cache-dir`.
   The directory is created if it doesn't exist."
       [inner cache-dir]
       [:any :string => :any]
       (ensure-dir! cache-dir)
       (->CachingBackend inner cache-dir))

(>defn enabled-by-env?
       "Returns true when the `DEEP_COOKIE_LLM_CACHE` env var is set to a truthy value (\"1\", \"true\", \"yes\")."
       []
       [=> :boolean]
       (boolean (#{"1" "true" "yes"} (System/getenv "DEEP_COOKIE_LLM_CACHE"))))
