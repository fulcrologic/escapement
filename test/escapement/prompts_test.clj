(ns escapement.prompts-test
  (:require
   [clojure.java.io :as io]
   [escapement.prompts :as prompts]
   [fulcro-spec.core :refer [specification assertions =throws=>]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-file [name content]
  (let [d (Files/createTempDirectory "prompts-test" (into-array FileAttribute []))
        f (str d "/" name)]
    (spit f content)
    f))

(specification "render — happy path"
               (assertions
                "substitutes a single {{VAR}} from a keyword key"
                (prompts/render "hello {{NAME}}" {:NAME "world"}) => "hello world"

                "substitutes multiple distinct tokens"
                (prompts/render "{{A}}/{{B}}/{{A}}" {:A 1 :B 2}) => "1/2/1"

                "accepts string keys"
                (prompts/render "x={{X}}" {"X" 7}) => "x=7"

                "coerces non-string values via str"
                (prompts/render "ns={{NS}}, fn={{FN}}" {:NS 'my.ns :FN :go!}) => "ns=my.ns, fn=:go!"

                "leaves non-matching curly text alone"
                (prompts/render "literal {x} and {{lower}} and {{Mixed}}" {}) => "literal {x} and {{lower}} and {{Mixed}}"

                "preserves $-signs and \\-signs in substitutions (no regex replacement leaks)"
                (prompts/render "cost {{P}}" {:P "$5 \\n"}) => "cost $5 \\n"))

(specification "render — missing token errors"
               (assertions
                "throws ex-info with :missing listing each unresolved token"
                (try (prompts/render "{{A}}-{{MISSING_ONE}}-{{B}}-{{MISSING_TWO}}" {:A 1 :B 2})
                     :no-throw
                     (catch clojure.lang.ExceptionInfo e
                       (ex-data e)))
                => {:missing  ["MISSING_ONE" "MISSING_TWO"]
                    :provided ["A" "B"]}

                "missing token error mentions all missing names in the message"
                (try (prompts/render "{{X}} {{Y}}" {})
                     (catch clojure.lang.ExceptionInfo e (.getMessage e)))
                => "Unresolved prompt tokens: {{X}}, {{Y}}"))

(specification "render-file — loads template from disk"
               (let [path (tmp-file "p.md" "function: {{FN}}\nnamespace: {{NS}}\n")]
                 (assertions
                  "renders content read from filesystem"
                  (prompts/render-file path {:FN "do-thing" :NS "my.app"})
                  => "function: do-thing\nnamespace: my.app\n")))
