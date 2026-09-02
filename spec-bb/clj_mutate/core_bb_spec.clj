(ns clj-mutate.core-bb-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.manifest :as manifest]
            [clj-mutate.source :as source]))

(describe "top-level form manifest under Babashka"
  (it "tracks top-level forms with ids, lines, and hashes"
    (let [forms (source/read-source-forms "(ns foo)\n(defn bar [] 42)\n(defmethod quux :x [] true)\n")
          form-manifest (manifest/top-level-form-manifest forms)
          bar (second form-manifest)]
      (should= "form/0/ns" (:id (first form-manifest)))
      (should= "defn/bar" (:id bar))
      (should= "defmethod/quux/:x" (:id (nth form-manifest 2)))
      (should= 2 (:line bar))
      (should-not-be-nil (:hash bar)))))

(run-specs)
