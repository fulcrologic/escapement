(ns escapement.llm.catalog-macros
  "Compile-time helper for `escapement.llm.catalog-source`. Reads the
   bundled `models-api.json` from the classpath at macro-expand time and
   emits its parsed contents as a Clojure data literal. CLJS uses this
   so the model catalog is available at runtime with no disk I/O and no
   reliance on cheshire/clojure.java.io in the JS environment.

   The macro body only runs on the JVM (cheshire + java.io); the file
   extension is .cljc only so CLJS can reach it via `:require-macros`."
  #?(:clj (:require [cheshire.core :as json]
                    [clojure.java.io :as io])))

#?(:clj
   (defmacro embedded-catalog
     "Expands to the parsed contents of `escapement/llm/models-api.json`
      (the bundled models.dev dump) as a Clojure data literal. The file
      is read and parsed once during compilation, so no I/O or
      JSON-parsing dependency leaks into the compiled CLJS output."
     []
     (let [data (-> (io/resource "escapement/llm/models-api.json")
                  slurp
                  (json/parse-string))]
       `(quote ~data))))
