(ns clj-mutate.selection-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.selection :as selection]))

(describe "select-mutation-sites"
  (it "filters covered sites to requested lines"
    (let [sites [{:line 3 :form-index 1} {:line 8 :form-index 2}]]
      (should= [{:line 3 :form-index 1}]
               (selection/select-mutation-sites sites #{3} false false nil))))

  (it "returns no sites when the module hash is unchanged"
    (should= [] (selection/select-mutation-sites [{:line 3}] nil true true #{1})))

  (it "filters to changed form indices for differential runs"
    (let [sites [{:line 3 :form-index 1} {:line 8 :form-index 2}]]
      (should= [{:line 8 :form-index 2}]
               (selection/select-mutation-sites sites nil true false #{2}))))

  (it "selects one mutation by readable or persistent identity"
    (let [sites [{:display-id "M001" :mutation-id "defn/a/1/rule"}
                 {:display-id "M002" :mutation-id "defn/b/2/rule"}]]
      (should= [(first sites)]
               (selection/select-mutation-sites sites nil "M001" false false nil))
      (should= [(second sites)]
               (selection/select-mutation-sites
                 sites nil "defn/b/2/rule" false false nil))))

  (it "returns all covered sites when no filter applies"
    (let [sites [{:line 3} {:line 8}]]
      (should= sites (selection/select-mutation-sites sites nil false false nil)))))

(describe "default-since-last-run?"
  (it "defaults to differential when a manifest exists and no override is set"
    (should (selection/default-since-last-run? nil false false {:module-hash "x"}))
    (should-not (selection/default-since-last-run? #{1} false false {:module-hash "x"}))
    (should-not (selection/default-since-last-run? nil false true {:module-hash "x"}))
    (should-not (selection/default-since-last-run? nil false false nil))
    (should-not (selection/default-since-last-run? nil false false false))
    (should-not (selection/default-since-last-run? nil false false true))))

(describe "differential-site-counts"
  (it "counts new and manifest-violating form mutations"
    (let [sites [{:form-index 0} {:form-index 1} {:form-index 1} {:form-index 2}]]
      (should= {:new-form-mutations 1
                :manifest-violating-form-mutations 2}
               (selection/differential-site-counts sites #{0} #{1})))))

(run-specs)
