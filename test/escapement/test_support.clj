(ns escapement.test-support
  "Tiny helpers shared by tests. Babashka-friendly — no JDK concurrency classes
   that SCI doesn't expose.")

(defn pop-first!
  "Pop the first element from an atom holding a vector. Returns nil when empty.
   FIFO; replaces the BlockingDeque/pollFirst pattern from older tests."
  [a]
  (let [[old _] (swap-vals! a (fn [v] (if (seq v) (subvec v 1) v)))]
    (first old)))

(defn push-last!
  "Append `x` to the atom-backed vector queue."
  [a x]
  (swap! a conj x))

(defn queue
  "Build an atom-backed FIFO queue, optionally seeded from a collection."
  ([] (atom []))
  ([xs] (atom (vec xs))))
