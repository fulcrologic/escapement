(ns com.example.matrix
  "3x3 matrix multiplication, target of the matrix-team demo.

   The EXPERIMENTER agent owns this file: it iterates on different
   implementations of `mult` aiming for the best wall-clock performance
   under criterium's `quick-bench`.

   The TESTER agent never edits this file; it only writes/runs
   correctness checks against the public `mult` here.

   Matrix shape contract:
     * inputs `a` and `b` are 3x3 matrices
     * representation is intentionally NOT pinned by this skeleton — the
       experimenter may use vectors of vectors, flat vectors, double-arrays,
       etc. — but the tester's tests pin a representation by example, and
       the experimenter MUST keep `mult` correct under that representation.")

(set! *unchecked-math* true)

(defn mult
  "Optimized with unchecked math and inline metadata to eliminate function call overhead."
  {:inline
   (fn [a b]
     `((fn [a# b#]
         (let [[[a00# a01# a02#]
                [a10# a11# a12#]
                [a20# a21# a22#]] a#
               [[b00# b01# b02#]
                [b10# b11# b12#]
                [b20# b21# b22#]] b#]
           [[(+ (* a00# b00#) (* a01# b10#) (* a02# b20#))
             (+ (* a00# b01#) (* a01# b11#) (* a02# b21#))
             (+ (* a00# b02#) (* a01# b12#) (* a02# b22#))]
            [(+ (* a10# b00#) (* a11# b10#) (* a12# b20#))
             (+ (* a10# b01#) (* a11# b11#) (* a12# b21#))
             (+ (* a10# b02#) (* a11# b12#) (* a12# b22#))]
            [(+ (* a20# b00#) (* a21# b10#) (* a22# b20#))
             (+ (* a20# b01#) (* a21# b11#) (* a22# b21#))
             (+ (* a20# b02#) (* a21# b12#) (* a22# b22#))]]))
       ~a ~b))}
  [a b]
  (let [[[a00 a01 a02]
         [a10 a11 a12]
         [a20 a21 a22]] a
        [[b00 b01 b02]
         [b10 b11 b12]
         [b20 b21 b22]] b]
    [[(+ (* a00 b00) (* a01 b10) (* a02 b20))
      (+ (* a00 b01) (* a01 b11) (* a02 b21))
      (+ (* a00 b02) (* a01 b12) (* a02 b22))]
     [(+ (* a10 b00) (* a11 b10) (* a12 b20))
      (+ (* a10 b01) (* a11 b11) (* a12 b21))
      (+ (* a10 b02) (* a11 b12) (* a12 b22))]
     [(+ (* a20 b00) (* a21 b10) (* a22 b20))
      (+ (* a20 b01) (* a21 b11) (* a22 b21))
      (+ (* a20 b02) (* a21 b12) (* a22 b22))]]))
