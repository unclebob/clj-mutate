(ns clj-mutate.execution-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.execution :as execution]
            [clj-mutate.report :as report]
            [clj-mutate.runner :as runner]
            [clj-mutate.source :as source]
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
                       "clj -M:spec"
                       report/print-progress)))]
      (should-contain "[  1/1] M001  KILLED" output)
      (should-contain "42:0" output)))

  (it "tests all mutations and returns results sorted by index"
    (let [sites [{:index 0 :original '+ :mutant '- :line 5 :description "+ -> -"}
                 {:index 1 :original '> :mutant '>= :line 7 :description "> -> >="}
                 {:index 2 :original '= :mutant 'not= :line 9 :description "= -> not="}]
          call-count (atom 0)]
      (with-redefs [execution/mutate-and-test-in-dir
                    (fn [_ _ _ site _ _]
                      (swap! call-count inc)
                      {:site site :result :killed :timeout? false})
                    workers/new-run-base-dir
                    (fn [root] (str root "/run-test"))
                    workers/create-worker-dirs!
                    (fn [base _ _ n]
                      (should= "target/mutation-workers/run-test" base)
                      (vec (repeat n "target/fake-worker")))
                    workers/cleanup-worker-dirs! (fn [_] nil)]
        (let [results (execution/run-mutations-parallel
                        sites "src/foo.cljc" "(ns foo)" 30000 nil "clj -M:spec")]
          (should= 3 (count results))
          (should= 3 @call-count)
          (should= [0 1 2] (mapv #(:index (:site %)) results))
          (should (every? #(= :killed (:result %)) results))))))

  (it "works with more mutations than workers"
    (let [sites (vec (for [i (range 10)]
                       {:index i :original '+ :mutant '- :line (+ 5 i)
                        :description (str "mut-" i)}))]
      (with-redefs [execution/mutate-and-test-in-dir
                    (fn [_ _ _ site _ _]
                      {:site site :result :killed :timeout? false})
                    workers/new-run-base-dir
                    (fn [root] (str root "/run-test"))
                    workers/create-worker-dirs!
                    (fn [base _ _ n]
                      (should= "target/mutation-workers/run-test" base)
                      (vec (repeat n "target/fake-worker")))
                    workers/cleanup-worker-dirs! (fn [_] nil)]
        (let [results (execution/run-mutations-parallel
                        sites "src/foo.cljc" "(ns foo)" 30000 nil "clj -M:spec")]
          (should= 10 (count results))
          (should= (vec (range 10)) (mapv #(:index (:site %)) results))))))

  (it "limits worker directory count when max-workers is provided"
    (let [sites (vec (for [i (range 5)]
                       {:index i :original '+ :mutant '- :line (+ 5 i)
                        :description (str "mut-" i)}))
          created-workers (atom nil)]
      (with-redefs [execution/mutate-and-test-in-dir
                    (fn [_ _ _ site _ _]
                      {:site site :result :killed :timeout? false})
                    workers/new-run-base-dir
                    (fn [root] (str root "/run-test"))
                    workers/create-worker-dirs!
                    (fn [_ _ _ n]
                      (reset! created-workers n)
                      (vec (repeat n "target/fake-worker")))
                    workers/cleanup-worker-dirs! (fn [_] nil)]
        (execution/run-mutations-parallel sites "src/foo.cljc" "(ns foo)" 30000 2 "clj -M:spec")
        (should= 2 @created-workers)))))

(describe "mutate-and-test"
  (it "writes mutated file, runs specs, restores original"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          original-content "(ns test-ns)\n(defn foo [] (+ 1 2))\n"]
      (spit temp-path original-content)
      (with-redefs [runner/run-specs (fn [& _] :killed)]
        (let [forms (source/read-source-forms original-content)
              sites (source/discover-mutations original-content)
              plus-site (first (filter #(= (:original %) '+) sites))
              result (execution/mutate-and-test temp-path original-content
                                                forms plus-site 30000 "clj -M:spec")]
          (should= :killed (:result result))
          (should= original-content (slurp temp-path))))
      (.delete temp-file))))

(describe "mutate-and-test-in-dir"
  (it "writes mutated content to worker dir, calls run-specs with dir, restores"
    (let [worker-dir (doto (java.io.File. (str "target/test-worker-" (System/nanoTime)))
                       (.mkdirs))
          worker-path (.getPath worker-dir)
          source-rel "src/test_ns.cljc"
          source-file (java.io.File. worker-dir source-rel)
          _ (.mkdirs (.getParentFile source-file))
          original-content "(ns test-ns)\n(defn foo [] (+ 1 2))\n"
          _ (spit (.getPath source-file) original-content)
          sites (source/discover-mutations original-content)
          plus-site (first (filter #(= (:original %) '+) sites))
          received-dir (atom nil)]
      (with-redefs [runner/run-specs (fn [timeout dir cmd]
                                       (should= "clj -M:spec" cmd)
                                       (reset! received-dir dir)
                                       (should-contain "(- 1 2)" (slurp (.getPath source-file)))
                                       :killed)]
        (let [result (execution/mutate-and-test-in-dir worker-path source-rel
                                                       original-content plus-site 30000 "clj -M:spec")]
          (should= :killed (:result result))
          (should= worker-path @received-dir)
          (should= original-content (slurp (.getPath source-file)))))
      (.delete source-file)
      (.delete (.getParentFile source-file))
      (.delete worker-dir))))

(run-specs)
