(ns clj-mutate.workflow-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.execution :as execution]
            [clj-mutate.workflow :as workflow]))

(describe "run-mutation-suite"
  (it "returns no results when there are no sites"
    (with-redefs [execution/run-mutations-parallel (fn [& _] (throw (Exception. "should not run")))]
      (should= [] (workflow/run-mutation-suite [] "src/foo.cljc" "(ns foo)" 30000 nil "clj -M:spec")))))

(run-specs)
