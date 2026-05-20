(ns com.example.matrix-test
  "Correctness suite for com.example.matrix/mult.

   Owned by the TESTER agent. The experimenter never edits this file."
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.example.matrix :as m]))

;; Reference implementation for spot-checks
(defn ref-mult [a b]
  "Naive reference implementation for verification."
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

(deftest identity-mult
  (testing "I * I = I"
    (let [I [[1 0 0] [0 1 0] [0 0 1]]]
      (is (= I (m/mult I I))))))

(deftest identity-times-matrix
  (testing "I * M = M"
    (let [I [[1 0 0] [0 1 0] [0 0 1]]
          M [[1 2 3] [4 5 6] [7 8 9]]]
      (is (= M (m/mult I M))))))

(deftest matrix-times-identity
  (testing "M * I = M"
    (let [I [[1 0 0] [0 1 0] [0 0 1]]
          M [[1 2 3] [4 5 6] [7 8 9]]]
      (is (= M (m/mult M I))))))

(deftest zero-matrix
  (testing "Zero matrix times any matrix = zero"
    (let [Z [[0 0 0] [0 0 0] [0 0 0]]
          M [[1 2 3] [4 5 6] [7 8 9]]]
      (is (= Z (m/mult Z M)))
      (is (= Z (m/mult M Z))))))

(deftest known-hand-computed
  (testing "Specific case with known result"
    (let [A        [[1 2 3] [4 5 6] [7 8 9]]
          B        [[9 8 7] [6 5 4] [3 2 1]]
          expected [[30 24 18] [84 69 54] [138 114 90]]]
      (is (= expected (m/mult A B))))))

(deftest negative-entries
  (testing "Mixed positive and negative entries"
    (let [A        [[1 -2 3] [-4 5 -6] [7 -8 9]]
          B        [[-1 2 -3] [4 -5 6] [-7 8 -9]]
          expected [[-30 36 -42] [66 -81 96] [-102 126 -150]]]
      (is (= expected (m/mult A B))))))

(deftest floating-point
  (testing "Floating-point inputs"
    (let [A        [[1.0 2.0 3.0] [4.0 5.0 6.0] [7.0 8.0 9.0]]
          B        [[0.5 0.5 0.5] [0.5 0.5 0.5] [0.5 0.5 0.5]]
          expected [[3.0 3.0 3.0] [7.5 7.5 7.5] [12.0 12.0 12.0]]]
      (is (= expected (m/mult A B))))))

(deftest ref-impl-agreement-random
  (testing "Reference implementation agreement on random-looking matrices"
    (let [cases [
                 [[1 2 3] [4 5 6] [7 8 9]]
                 [[2 0 1] [3 1 0] [0 2 1]]
                 [[10 -3 2] [0 5 -1] [4 0 2]]
                 ]]
      (doseq [A cases, B cases]
        (is (= (ref-mult A B) (m/mult A B)))))))
