(ns clj-mutate.core-bb-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.core :as core]))

(describe "top-level form manifest under Babashka"
  (it "tracks top-level forms with ids, lines, and hashes"
    (let [forms (core/read-source-forms "(ns foo)\n(defn bar [] 42)\n(defmethod quux :x [] true)\n")
          manifest (core/top-level-form-manifest forms)
          bar (second manifest)]
      (should= "form/0/ns" (:id (first manifest)))
      (should= "defn/bar" (:id bar))
      (should= "defmethod/quux/:x" (:id (nth manifest 2)))
      (should= 2 (:line bar))
      (should-not-be-nil (:hash bar)))))

(run-specs)
