(ns clj-mutate.core-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.core :as core]
            [clj-mutate.coverage :as coverage]
            [clj-mutate.runner :as runner]
            [clj-mutate.workers :as workers]))

(describe "read-source-forms"
  (it "reads Clojure forms from a string"
    (let [forms (core/read-source-forms "(ns foo) (defn bar [] 42)")]
      (should= 2 (count forms))
      (should= 'ns (first (first forms))))))

(describe "discover-all-mutations"
  (it "finds mutations across multiple forms"
    (let [forms (core/read-source-forms "(defn foo [] (+ 1 2)) (defn bar [] (> x 0))")
          sites (core/discover-all-mutations forms)]
      (should (some #(= (:original %) '+) sites))
      (should (some #(= (:original %) '>) sites))
      (should (some #(= (:original %) 1) sites)))))

(describe "mutate-source-text"
  (it "preserves comments and indentation"
    (let [src "(ns foo)\n;; a comment\n(defn bar [] (+ 1 2))\n"
          forms (core/read-source-forms src)
          sites (core/discover-all-mutations forms)
          plus-site (first (filter #(= (:original %) '+) sites))
          result (core/mutate-source-text src plus-site)]
      (should-contain ";; a comment" result)
      (should-contain "(- 1 2)" result)))

  (it "replaces only the targeted token"
    (let [src "(defn f [] (+ 1 (+ 2 3)))\n"
          forms (core/read-source-forms src)
          sites (core/discover-all-mutations forms)
          first-plus (first (filter #(= (:original %) '+) sites))
          result (core/mutate-source-text src first-plus)]
      (should-contain "(- 1 (+ 2 3))" result)))

  (it "= does not match inside not="
    (let [src "(defn f [] (not= x y))\n"
          forms (core/read-source-forms src)
          sites (core/discover-all-mutations forms)
          eq-sites (filter #(and (= (:original %) '=) (= (:mutant %) 'not=)) sites)]
      (should= 0 (count eq-sites))))

  (it "0 does not match inside 10"
    (let [src "(defn f [] (+ 10 x))\n"
          forms (core/read-source-forms src)
          sites (core/discover-all-mutations forms)
          zero-sites (filter #(and (= (:original %) 0) (= (:mutant %) 1)) sites)]
      (should= 0 (count zero-sites))))

  (it "preserves trailing newline"
    (let [src "(defn f [] (+ 1 2))\n"
          forms (core/read-source-forms src)
          sites (core/discover-all-mutations forms)
          plus-site (first (filter #(= (:original %) '+) sites))
          result (core/mutate-source-text src plus-site)]
      (should (.endsWith result "\n")))))

(describe "mutate-and-test"
  (it "writes mutated file, runs specs, restores original"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          original-content "(ns test-ns)\n(defn foo [] (+ 1 2))\n"]
      (spit temp-path original-content)
      (with-redefs [runner/run-specs (fn [_] :killed)]
        (let [forms (core/read-source-forms original-content)
              sites (core/discover-all-mutations forms)
              plus-site (first (filter #(= (:original %) '+) sites))
              result (core/mutate-and-test temp-path original-content
                                           forms plus-site 30000)]
          (should= :killed (:result result))
          (should= original-content (slurp temp-path))))
      (.delete temp-file))))

(describe "validate-args"
  (it "returns error when no args given"
    (let [result (core/validate-args [])]
      (should-contain :error result)))

  (it "returns error when source file doesn't exist"
    (let [result (core/validate-args ["nonexistent.cljc"])]
      (should-contain :error result)))

  (it "returns source-path when file exists"
    (let [temp (java.io.File/createTempFile "src" ".cljc")]
      (spit temp "(ns test-ns)")
      (let [result (core/validate-args [(.getPath temp)])]
        (should= (.getPath temp) (:source-path result))
        (.delete temp)))))

(describe "partition-by-coverage"
  (it "separates covered from uncovered sites"
    (let [sites [{:line 1 :original '+} {:line 2 :original '-} {:line 3 :original '>}]
          covered-lines #{1 3}
          [covered uncovered] (core/partition-by-coverage sites covered-lines)]
      (should= 2 (count covered))
      (should= 1 (count uncovered))
      (should= 2 (:line (first uncovered)))))

  (it "treats nil-line sites as covered"
    (let [sites [{:line nil :original '+} {:line 5 :original '-}]
          covered-lines #{5}
          [covered uncovered] (core/partition-by-coverage sites covered-lines)]
      (should= 2 (count covered))
      (should= 0 (count uncovered))))

  (it "treats all sites as covered when coverage is nil"
    (let [sites [{:line 1 :original '+} {:line 2 :original '-}]
          [covered uncovered] (core/partition-by-coverage sites nil)]
      (should= 2 (count covered))
      (should= 0 (count uncovered)))))

(describe "format-report"
  (it "produces summary with kill count"
    (let [results [{:site {:description "+ -> -"} :result :killed}
                   {:site {:description "1 -> 0"} :result :survived}]
          report (core/format-report "src/empire/foo.cljc" results 0)]
      (should-contain "1/2 mutants killed" report)
      (should-contain "SURVIVED" report)
      (should-contain "KILLED" report)))

  (it "includes line numbers in progress lines"
    (let [results [{:site {:description "+ -> -" :line 42} :result :killed}
                   {:site {:description "1 -> 0" :line 99 :index 1} :result :survived}]
          report (core/format-report "src/empire/foo.cljc" results 0)]
      (should-contain "L42" report)
      (should-contain "L99" report)))

  (it "includes line numbers in survivor summary"
    (let [results [{:site {:description "1 -> 0" :line 207 :index 0} :result :survived}]
          report (core/format-report "src/empire/foo.cljc" results 0)]
      (should-contain "L207" report)))

  (it "includes uncovered count in summary"
    (let [results [{:site {:description "+ -> -"} :result :killed}]
          report (core/format-report "src/empire/foo.cljc" results 3)]
      (should-contain "1/1 mutants killed" report)
      (should-contain "3 uncovered" report))))

(describe "extract-mutation-date"
  (it "returns nil when no mutation comment exists"
    (should= nil (core/extract-mutation-date "(ns foo)\n(defn bar [] 42)")))

  (it "extracts date from mutation comment at start of file"
    (should= "2026-02-22"
             (core/extract-mutation-date
               ";; mutation-tested: 2026-02-22\n(ns foo)\n(defn bar [] 42)")))

  (it "returns nil when comment is not at start of file"
    (should= nil
             (core/extract-mutation-date
               "(ns foo)\n;; mutation-tested: 2026-02-22\n(defn bar [] 42)"))))

(describe "stamp-mutation-date"
  (it "adds date comment to file without one"
    (should= ";; mutation-tested: 2026-02-22\n(ns foo)\n(defn bar [] 42)"
             (core/stamp-mutation-date "(ns foo)\n(defn bar [] 42)" "2026-02-22")))

  (it "replaces existing date comment"
    (should= ";; mutation-tested: 2026-02-23\n(ns foo)\n(defn bar [] 42)"
             (core/stamp-mutation-date
               ";; mutation-tested: 2026-02-22\n(ns foo)\n(defn bar [] 42)"
               "2026-02-23"))))

(describe "run-mutation-testing stamps date"
  (it "stamps the source file with today's date after testing"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          original "(ns test-ns)\n(defn foo [] (+ 1 2))\n"]
      (spit temp-path original)
      (with-redefs [runner/run-specs (fn [& _] :killed)
                    runner/run-specs-timed (fn [] {:result :survived :elapsed-ms 100})
                    coverage/load-coverage (fn [_] nil)
                    core/run-mutations-parallel
                    (fn [sites source-path content timeout-ms]
                      (doall (map (fn [site]
                                    (core/mutate-and-test source-path content nil site timeout-ms))
                                  sites)))]
        (core/run-mutation-testing temp-path)
        (let [stamped (slurp temp-path)]
          (should-not-be-nil (core/extract-mutation-date stamped))))
      (.delete temp-file)))

  (it "cleans up stale worker directories at startup"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          original "(ns test-ns)\n(defn foo [] (+ 1 2))\n"
          cleanup-args (atom [])]
      (spit temp-path original)
      (with-redefs [workers/cleanup-worker-dirs!
                    (fn [base-dir] (swap! cleanup-args conj base-dir))
                    runner/run-specs (fn [& _] :killed)
                    runner/run-specs-timed (fn [] {:result :survived :elapsed-ms 100})
                    coverage/load-coverage (fn [_] nil)
                    core/run-mutations-parallel
                    (fn [sites source-path content timeout-ms]
                      (doall (map (fn [site]
                                    (core/mutate-and-test source-path content nil site timeout-ms))
                                  sites)))]
        (core/run-mutation-testing temp-path)
        (should= ["target/mutation-workers"] @cleanup-args))
      (.delete temp-file)))

  (it "reports previous mutation test date"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          original ";; mutation-tested: 2026-01-15\n(ns test-ns)\n(defn foo [] (+ 1 2))\n"]
      (spit temp-path original)
      (with-redefs [runner/run-specs (fn [& _] :killed)
                    runner/run-specs-timed (fn [] {:result :survived :elapsed-ms 100})
                    coverage/load-coverage (fn [_] nil)
                    core/run-mutations-parallel
                    (fn [sites source-path content timeout-ms]
                      (doall (map (fn [site]
                                    (core/mutate-and-test source-path content nil site timeout-ms))
                                  sites)))]
        (let [captured (with-out-str
                         (core/run-mutation-testing temp-path))]
          (should-contain "Previous mutation test: 2026-01-15" captured)))
      (.delete temp-file))))

(describe "line numbers stable across stamp"
  (it "reported survivor lines from full run work with --lines"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          original "(ns test-ns)\n(defn foo [] (+ 1 2))\n"]
      (spit temp-path original)
      (with-redefs [runner/run-specs (fn [& _] :survived)
                    runner/run-specs-timed (fn [] {:result :survived :elapsed-ms 100})
                    coverage/load-coverage (fn [_] nil)
                    core/run-mutations-parallel
                    (fn [sites source-path content timeout-ms]
                      (let [results (doall (map-indexed
                                             (fn [i site]
                                               (let [r (core/mutate-and-test source-path content nil site timeout-ms)]
                                                 (#'core/print-progress i (count sites) r site)
                                                 r))
                                             sites))]
                        results))]
        (let [report (with-out-str
                       (core/run-mutation-testing temp-path))
              plus-match (re-find #"L(\d+)\s+\+ -> -" report)
              reported-line (when plus-match (parse-long (second plus-match)))]
          (should-not-be-nil reported-line)
          (let [lines-report (with-out-str
                               (core/run-mutation-testing temp-path
                                                         #{reported-line}))]
            (should-contain "+ -> -" lines-report))))
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
          forms (core/read-source-forms original-content)
          sites (core/discover-all-mutations forms)
          plus-site (first (filter #(= (:original %) '+) sites))
          received-dir (atom nil)]
      (with-redefs [runner/run-specs (fn [timeout dir]
                                       (reset! received-dir dir)
                                       ;; Verify mutated content is on disk
                                       (should-contain "(- 1 2)" (slurp (.getPath source-file)))
                                       :killed)]
        (let [result (core/mutate-and-test-in-dir worker-path source-rel
                                                   original-content plus-site 30000)]
          (should= :killed (:result result))
          (should= worker-path @received-dir)
          ;; Original content should be restored
          (should= original-content (slurp (.getPath source-file)))))
      ;; Cleanup
      (.delete source-file)
      (.delete (.getParentFile source-file))
      (.delete worker-dir))))

(describe "integration: discover mutations in a real source file"
  (it "finds mutation sites in mutations.cljc"
    (let [content (slurp "src/clj_mutate/mutations.cljc")
          forms (core/read-source-forms content)
          sites (core/discover-all-mutations forms)]
      (should (> (count sites) 0))
      (println (format "Found %d mutation sites in mutations.cljc" (count sites))))))

(describe "run-mutations-parallel"
  (it "tests all mutations and returns results sorted by index"
    (let [sites [{:index 0 :original '+ :mutant '- :line 5 :description "+ -> -"}
                 {:index 1 :original '> :mutant '>= :line 7 :description "> -> >="}
                 {:index 2 :original '= :mutant 'not= :line 9 :description "= -> not="}]
          call-count (atom 0)]
      (with-redefs [core/mutate-and-test-in-dir
                    (fn [_ _ _ site _]
                      (swap! call-count inc)
                      {:site site :result :killed :timeout? false})
                    workers/create-worker-dirs!
                    (fn [_ _ _ n] (vec (repeat n "target/fake-worker")))
                    workers/cleanup-worker-dirs! (fn [_] nil)]
        (let [results (core/run-mutations-parallel
                        sites "src/foo.cljc" "(ns foo)" 30000)]
          (should= 3 (count results))
          (should= 3 @call-count)
          ;; Results sorted by index
          (should= [0 1 2] (mapv #(:index (:site %)) results))
          ;; All killed
          (should (every? #(= :killed (:result %)) results))))))

  (it "works with more mutations than workers"
    (let [sites (vec (for [i (range 10)]
                       {:index i :original '+ :mutant '- :line (+ 5 i)
                        :description (str "mut-" i)}))]
      (with-redefs [core/mutate-and-test-in-dir
                    (fn [_ _ _ site _]
                      {:site site :result :killed :timeout? false})
                    workers/create-worker-dirs!
                    (fn [_ _ _ n] (vec (repeat n "target/fake-worker")))
                    workers/cleanup-worker-dirs! (fn [_] nil)]
        (let [results (core/run-mutations-parallel
                        sites "src/foo.cljc" "(ns foo)" 30000)]
          (should= 10 (count results))
          (should= (vec (range 10)) (mapv #(:index (:site %)) results)))))))

(run-specs)
