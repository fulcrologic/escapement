(ns escapement.storage.common
  "Host-portable pieces shared by the built-in `ArtifactStore` backends (disk and memory): content-type
   guessing, UTF-8 conversion, and the one rule for assembling an artifact summary from what the path
   implies plus what the caller persisted as meta."
  (:require
    [clojure.string :as str]))

(def binary-content-type
  "The content-type recorded for bytes written without an explicit `:artifact/content-type`."
  "application/octet-stream")

(defn path->content-type
  "Returns the content-type implied by the suffix of artifact `path`."
  [path]
  (cond
    (str/ends-with? path ".json") "application/json"
    (str/ends-with? path ".edn") "application/edn"
    (str/ends-with? path ".md") "text/markdown"
    :else "text/plain"))

(defn utf8-bytes
  "Returns the UTF-8 encoding of string `s` (a byte array; a `Uint8Array` in CLJS)."
  [s]
  #?(:clj  (.getBytes ^String s "UTF-8")
     :cljs (.encode (js/TextEncoder.) s)))

(defn utf8-string
  "Returns the string decoded from the UTF-8 `content` bytes."
  [content]
  #?(:clj  (String. ^bytes content "UTF-8")
     :cljs (.decode (js/TextDecoder. "utf-8") content)))

(defn byte-count
  "Returns the number of bytes in `content` (a byte array or `Uint8Array`)."
  [content]
  #?(:clj  (alength ^bytes content)
     :cljs (.-length content)))

(defn binary-meta
  "Returns `meta` with `:artifact/content-type` defaulted to `binary-content-type`, the meta a byte
   write persists."
  [meta]
  (cond-> (or meta {})
    (nil? (:artifact/content-type meta)) (assoc :artifact/content-type binary-content-type)))

(defn artifact-summary
  "Returns the summary map for the artifact stored at `path` with `size` bytes: the caller's persisted
   `meta` merged over the `derived` coordinates (what the path implies), with `:artifact/path` and
   `:artifact/size` always taken from the stored content and `:artifact/content-type` taken from
   `meta`, else `derived`, else the path suffix."
  [path size derived meta]
  (merge derived
    meta
    {:artifact/path         path
     :artifact/size         size
     :artifact/content-type (or (:artifact/content-type meta)
                              (:artifact/content-type derived)
                              (path->content-type path))}))
