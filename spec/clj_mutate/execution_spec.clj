(ns clj-mutate.execution-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.execution :as execution]
            [clj-mutate.workers :as workers]))

(describe "run-mutations-parallel"
  (it "returns no results when there are no mutation sites"
    (let [created-workers (atom nil)]
      (with-redefs [workers/new-run-base-dir (fn [_] "target/mutation-workers/run-test")
                    workers/create-worker-dirs! (fn [_ _ _ n]
                                                  (reset! created-workers n)
                                                  ["target/fake-worker"])
                    workers/cleanup-worker-dirs! (fn [_] nil)]
        (should= [] (execution/run-mutations-parallel [] "src/foo.cljc" "(ns foo)" 30000 nil "clj -M:spec"))
        (should= 1 @created-workers))))

  (it "reports progress while running mutation sites"
    (let [output (with-out-str
                   (with-redefs [execution/mutate-and-test-in-dir
                                 (fn [_ _ _ site _ _]
                                   {:site site :result :killed :timeout? false})
                                 workers/new-run-base-dir (fn [_] "target/mutation-workers/run-test")
                                 workers/create-worker-dirs! (fn [_ _ _ _] ["target/fake-worker"])
                                 workers/cleanup-worker-dirs! (fn [_] nil)]
                     (execution/run-mutations-parallel
                       [{:index 0 :line 42 :description "+ -> -" :original '+ :mutant '-}]
                       "src/foo.cljc"
                       "(ns foo)"
                       30000
                       nil
                       "clj -M:spec")))]
      (should-contain "[  1/1] KILLED" output)
      (should-contain "L42" output))))

(run-specs)
