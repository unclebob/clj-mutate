(ns clj-mutate.workflow-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.backup :as backup]
            [clj-mutate.cli :as cli]
            [clj-mutate.coverage :as coverage]
            [clj-mutate.execution :as execution]
            [clj-mutate.manifest :as manifest]
            [clj-mutate.report :as report]
            [clj-mutate.runner :as runner]
            [clj-mutate.selection :as selection]
            [clj-mutate.source :as source]
            [clj-mutate.workflow :as workflow]))

(describe "run-mutation-suite"
  (it "returns no results when there are no sites"
    (with-redefs [execution/run-mutations-parallel (fn [& _] (throw (Exception. "should not run")))]
      (should= [] (workflow/run-mutation-suite [] "src/foo.cljc" "(ns foo)" 30000 nil "clj -M:spec")))))

(describe "run-mutation-testing embeds manifest"
  (it "writes the footer manifest after a full run"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          original "(ns test-ns)\n(defn foo [] (+ 1 2))\n"]
      (spit temp-path original)
      (with-redefs [runner/run-specs (fn [& _] :killed)
                    runner/run-specs-timed (fn [cmd]
                                             (should= (cli/default-test-command) cmd)
                                             {:result :survived :elapsed-ms 100})
                    coverage/load-coverage (fn [& _] nil)
                    execution/run-mutations-parallel
                    (fn [sites source-path content timeout-ms max-workers test-command & _]
                      (should= nil max-workers)
                      (should= (cli/default-test-command) test-command)
                      (doall (map (fn [site]
                                    (execution/mutate-and-test source-path content nil site timeout-ms test-command))
                                  sites)))]
        (workflow/run-mutation-testing temp-path)
        (let [updated (slurp temp-path)]
          (should-not-be-nil (manifest/extract-embedded-manifest updated))
          (should= (manifest/module-hash (manifest/strip-mutation-metadata updated))
                   (:module-hash (manifest/extract-embedded-manifest updated)))
          (should-contain "clj-mutate-manifest-begin" updated)))
      (.delete temp-file)))

  (it "reports previous mutation test date"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          original (manifest/embed-mutation-manifest
                     "(ns test-ns)\n(defn foo [] (+ 1 2))\n"
                     {:version 1
                      :tested-at "2026-01-15T09:30:00-06:00"
                      :module-hash "module-123"
                      :forms [{:id "form/0/ns" :hash "ns"}
                              {:id "defn/foo" :hash "foo"}]})]
      (spit temp-path original)
      (with-redefs [runner/run-specs (fn [& _] :killed)
                    runner/run-specs-timed (fn [cmd]
                                             (should= (cli/default-test-command) cmd)
                                             {:result :survived :elapsed-ms 100})
                    coverage/load-coverage (fn [& _] nil)
                    execution/run-mutations-parallel
                    (fn [sites source-path content timeout-ms max-workers test-command & _]
                      (should= nil max-workers)
                      (should= (cli/default-test-command) test-command)
                      (doall (map (fn [site]
                                    (execution/mutate-and-test source-path content nil site timeout-ms test-command))
                                  sites)))]
        (let [captured (with-out-str
                         (workflow/run-mutation-testing temp-path))]
          (should-contain "Previous mutation test: 2026-01-15T09:30:00-06:00" captured)))
      (.delete temp-file)))

  (it "filters to changed top-level forms with --since-last-run"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          initial "(ns test-ns)\n(defn unchanged [] (+ 1 2))\n(defn changed [] (+ 3 4))\n"
          updated "(ns test-ns)\n(defn unchanged [] (+ 1 2))\n(defn changed [] (+ 30 4))\n(defn added [] (+ 5 6))\n"
          prior-manifest (manifest/build-embedded-manifest (source/read-source-forms initial) "2026-02-20T08:00:00-06:00")
          captured-sites (atom nil)]
      (spit temp-path (manifest/embed-mutation-manifest updated prior-manifest))
      (with-redefs [runner/run-specs (fn [& _] :killed)
                    runner/run-specs-timed (fn [_] {:result :survived :elapsed-ms 100})
                    coverage/load-coverage (fn [& _] nil)
                    workflow/mutation-run-context
                    (fn [_ _ _]
                      {:prev-date "2026-02-20T08:00:00-06:00"
                       :prior-manifest prior-manifest
                       :analysis-content updated
                       :all-sites (source/discover-all-mutations (source/read-source-forms updated))
                       :covered-sites (source/discover-all-mutations (source/read-source-forms updated))
                       :uncovered []
                       :module-unchanged? false
                       :changed-forms #{2 3}
                       :manifest-content (manifest/embed-mutation-manifest updated prior-manifest)
                       :manifest-exists? true
                       :module-hash-changed? true
                       :changed-mutation-sites 4
                       :surface-area-counts {:new-form-mutations 2
                                             :manifest-violating-form-mutations 2}
                       :coverage-status {:lcov-path "target/coverage/lcov.info"
                                         :status :stale-reused
                                         :exists? true
                                         :last-modified 123
                                         :source-newer? true}
                       :original-content updated})
                    execution/run-mutations-parallel
                    (fn [sites _ _ _ _ _ & _]
                      (reset! captured-sites sites)
                      (mapv (fn [site] {:site site :result :killed :timeout? false}) sites))]
        (let [output (with-out-str
                       (workflow/run-mutation-testing temp-path nil 10 "clj -M:spec" nil true false 50 true))]
          (should-contain "Total mutation sites: 4" output)
          (should-contain "Covered mutation sites: 4" output)
          (should-contain "Uncovered mutation sites: 0" output)
          (should-contain "Changed mutation sites: 4" output)
          (should-contain "Manifest exists: yes" output)
          (should-contain "Module hash changed: yes" output)
          (should-contain "Reusing stale LCOV generated" output)
          (should-contain "source or test files have changed" output)
          (should-contain "Differential surface area: 2 mutations in new top-level forms" output)
          (should-contain "Manifest-violating surface area: 2 mutations" output))
        (should (seq @captured-sites))
        (should= #{2 3} (set (map :form-index @captured-sites)))
        (should (re-find #"\d{4}-\d{2}-\d{2}T" (:tested-at (manifest/extract-embedded-manifest (slurp temp-path))))))
      (.delete temp-file)))

  (it "short-circuits --since-last-run when the module hash is unchanged"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          source "(ns test-ns)\n(defn unchanged [] (+ 1 2))\n"
          prior-manifest (manifest/build-embedded-manifest
                           source "2026-02-20T08:00:00-06:00"
                           {:verified? true
                            :provenance (workflow/mutation-provenance "clj -M:spec")})
          source-with-manifest (manifest/embed-mutation-manifest source prior-manifest)
          called? (atom false)]
      (spit temp-path source-with-manifest)
      (with-redefs [runner/run-specs (fn [& _] (throw (Exception. "should not run")))
                    runner/run-specs-timed (fn [_] (throw (Exception. "should not run")))
                    coverage/load-coverage (fn [& _] (throw (Exception. "should not run")))
                    execution/run-mutations-parallel
                    (fn [& _]
                      (reset! called? true)
                      [])]
        (let [output (with-out-str
                       (workflow/run-mutation-testing temp-path nil 10 "clj -M:spec" nil true))]
          (should= false @called?)
          (should-contain "No changes since the successful mutation run" output)
          (should-contain "No mutations to test." output)
          (should-not-contain "Baseline:" output)
          (should-not-contain "0/0" output)
          (should= source-with-manifest (slurp temp-path))))
      (.delete temp-file)))

  (it "defaults to differential mutation when a manifest exists"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          initial "(ns test-ns)\n(defn unchanged [] (+ 1 2))\n(defn changed [] (+ 3 4))\n"
          updated "(ns test-ns)\n(defn unchanged [] (+ 1 2))\n(defn changed [] (+ 30 4))\n"
          prior-manifest (manifest/build-embedded-manifest
                           initial "2026-02-20T08:00:00-06:00"
                           {:verified? true
                            :provenance (workflow/mutation-provenance (cli/default-test-command))})
          captured-sites (atom nil)]
      (spit temp-path (manifest/embed-mutation-manifest updated prior-manifest))
      (with-redefs [runner/run-specs (fn [& _] :killed)
                    runner/run-specs-timed (fn [_] {:result :survived :elapsed-ms 100})
                    coverage/load-coverage (fn [& _] nil)
                    execution/run-mutations-parallel
                    (fn [sites _ _ _ _ _ & _]
                      (reset! captured-sites sites)
                      (mapv (fn [site] {:site site :result :killed :timeout? false}) sites))]
        (let [output (with-out-str (workflow/run-mutation-testing temp-path))]
          (should (seq @captured-sites))
          (should (every? #(= 2 (:form-index %)) @captured-sites))
          (should-contain "Filtering to changed top-level forms" output)))
      (.delete temp-file)))

  (it "runs an explicitly selected mutation even when a trusted manifest is unchanged"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          content "(ns test-ns)\n(defn unchanged [] (+ 1 2))\n"
          test-command (cli/default-test-command)
          prior-manifest (manifest/build-embedded-manifest
                           content "2026-02-20T08:00:00-06:00"
                           {:verified? true
                            :provenance (workflow/mutation-provenance test-command)})
          captured-sites (atom nil)]
      (spit temp-path (manifest/embed-mutation-manifest content prior-manifest))
      (try
        (with-redefs [runner/run-specs-timed
                      (fn [_] {:result :survived :elapsed-ms 100})
                      coverage/load-coverage
                      (fn [& _] {:lines nil :status :coverage-disabled})
                      execution/run-mutations-parallel
                      (fn [sites _ _ _ _ _ & _]
                        (reset! captured-sites sites)
                        (mapv (fn [site]
                                {:site site :result :killed :timeout? false})
                              sites))]
          (let [result (workflow/run-mutation-testing
                         temp-path nil 10 test-command nil false false 100
                         false "M001" false)]
            (should= :passed (:status result))
            (should= ["M001"] (mapv :display-id @captured-sites))))
        (finally (.delete temp-file)))))

  (it "uses --mutate-all to override default differential mutation"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          source "(ns test-ns)\n(defn foo [] (+ 1 2))\n"
          prior-manifest (manifest/build-embedded-manifest (source/read-source-forms source) "2026-02-20T08:00:00-06:00")
          captured-sites (atom nil)]
      (spit temp-path (manifest/embed-mutation-manifest source prior-manifest))
      (with-redefs [runner/run-specs (fn [& _] :killed)
                    runner/run-specs-timed (fn [_] {:result :survived :elapsed-ms 100})
                    coverage/load-coverage (fn [& _] nil)
                    execution/run-mutations-parallel
                    (fn [sites _ _ _ _ _ & _]
                      (reset! captured-sites sites)
                      (mapv (fn [site] {:site site :result :killed :timeout? false}) sites))]
        (let [output (with-out-str
                       (workflow/run-mutation-testing temp-path nil 10 "clj -M:spec" nil false true 50))]
          (should= 2 (count @captured-sites))
          (should-not (re-find #"changed top-level forms" output))))
      (.delete temp-file)))

  (it "prints a warning when mutation count exceeds the threshold"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          source "(ns test-ns)\n(defn foo [] (+ 1 2))\n"]
      (spit temp-path source)
      (with-redefs [runner/run-specs (fn [& _] :killed)
                    runner/run-specs-timed (fn [_] {:result :survived :elapsed-ms 100})
                    coverage/load-coverage (fn [& _] nil)
                    execution/run-mutations-parallel
                    (fn [sites _ _ _ _ _ & _]
                      (mapv (fn [site] {:site site :result :killed :timeout? false}) sites))]
        (let [output (with-out-str
                       (workflow/run-mutation-testing temp-path nil 10 "clj -M:spec" nil false false 1))]
          (should-contain "WARNING: Found 2 mutations. Consider splitting this module." output)))
      (.delete temp-file))))

(describe "run-mutation-testing options"
  (it "uses timeout-factor and test-command"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          original "(ns test-ns)\n(defn foo [] (+ 1 2))\n"
          captured-timeout (atom nil)
          captured-command (atom nil)]
      (spit temp-path original)
      (with-redefs [runner/run-specs (fn [& _] :killed)
                    runner/run-specs-timed (fn [cmd]
                                             (reset! captured-command cmd)
                                             {:result :survived :elapsed-ms 200})
                    coverage/load-coverage (fn [& _] nil)
                    execution/run-mutations-parallel
                    (fn [sites _ _ timeout-ms _ test-command & _]
                      (reset! captured-timeout timeout-ms)
                      (reset! captured-command test-command)
                      (mapv (fn [site] {:site site :result :killed :timeout? false}) sites))]
        (workflow/run-mutation-testing temp-path nil 3 "clj -M:all-tests" nil)
        (should= 600 @captured-timeout)
        (should= "clj -M:all-tests" @captured-command))
      (.delete temp-file))))

(describe "run-mutation-testing arities"
  (it "accepts a since-last-run argument without requiring mutate-all and mutation-warning"
    (let [captured (atom nil)]
      (with-redefs [backup/restore-from-backup! (fn [_] false)
                    manifest/extract-embedded-manifest (fn [_] nil)
                    workflow/mutation-run-context
                    (fn [_ _ _]
                      {:prev-date nil
                       :prior-manifest nil
                       :analysis-content "(ns test-ns)\n"
                       :all-sites []
                       :covered-sites []
                       :uncovered []
                       :module-unchanged? false
                       :changed-forms #{}
                       :manifest-content "(ns test-ns)\n"
                       :manifest-exists? false
                       :module-hash-changed? nil
                       :changed-mutation-sites 0
                       :surface-area-counts {:new-form-mutations 0
                                             :manifest-violating-form-mutations 0}
                       :coverage-status {:lcov-path "target/coverage/lcov.info"
                                         :exists? false
                                         :last-modified nil
                                         :source-newer? false}
                       :original-content "(ns test-ns)\n"})
                    selection/select-mutation-sites (fn [& _] [])
                    report/print-run-header (fn [& _] nil)
                    workflow/with-baseline (fn [test-command timeout-factor on-pass]
                                             (reset! captured {:test-command test-command
                                                               :timeout-factor timeout-factor})
                                             (on-pass 100))
                    report/print-uncovered (fn [_] nil)
                    backup/save-backup! (fn [& _] nil)
                    workflow/run-mutation-suite (fn [& _] [])
                    report/summarize-results (fn [& _] nil)
                    backup/cleanup-backup! (fn [& _] nil)
                    spit (fn [& _] nil)
                    slurp (fn [_] "(ns test-ns)\n")]
        (let [result (workflow/run-mutation-testing "src/test.cljc" nil 7 "clj -M:custom" nil true)]
          (should= :passed (:status result))
          (should-be-nil @captured))))))

(describe "scan-mutation-sites"
  (it "reports total and changed mutation sites with a warning"
    (let [source "(ns test-ns)\n(defn foo [] (+ 1 2))\n"
          prior (manifest/build-embedded-manifest source
                                                  "2026-02-20T08:00:00-06:00")
          updated "(ns test-ns)\n(defn foo [] (+ 1 20))\n"
          content (manifest/embed-mutation-manifest updated prior)
          output (with-out-str
                   (with-redefs [slurp (fn [_] content)]
                     (workflow/scan-mutation-sites "src/test.cljc" 1)))]
      (should-contain "=== Mutation Scan: src/test.cljc ===" output)
      (should-contain "Found 2 mutation sites." output)
      (should-contain "Changed mutation sites: 2" output)
      (should-contain "WARNING: Found 2 mutations. Consider splitting this module." output)))

  (it "reports zero changed mutation sites when the module hash is unchanged"
    (let [source "(ns test-ns)\n(defn foo [] (+ 1 2))\n"
          prior (manifest/build-embedded-manifest source
                                                  "2026-02-20T08:00:00-06:00")
          content (manifest/embed-mutation-manifest source prior)
          output (with-out-str
                   (with-redefs [slurp (fn [_] content)]
                     (workflow/scan-mutation-sites "src/test.cljc" 50)))]
      (should-contain "Found 2 mutation sites." output)
      (should-contain "Changed mutation sites: 0" output))))

(describe "update-manifest!"
  (it "rewrites the embedded manifest for the current file content"
    (let [temp-file (java.io.File/createTempFile "manifest" ".cljc")
          temp-path (.getPath temp-file)
          original "(ns test-ns)\n(defn foo [] (+ 1 2))\n"
          prior (manifest/build-embedded-manifest original
                                                  "2026-02-20T08:00:00-06:00")
          stamped (manifest/embed-mutation-manifest "(ns test-ns)\n(defn foo [] (+ 1 20))\n" prior)]
      (spit temp-path stamped)
      (with-redefs [manifest/now-str (fn [] "2026-03-12T12:00:00-05:00")]
        (workflow/update-manifest! temp-path))
      (let [updated (slurp temp-path)
            embedded (manifest/extract-embedded-manifest updated)
            analysis-content (manifest/strip-mutation-metadata updated)]
        (should= "2026-03-12T12:00:00-05:00" (:tested-at embedded))
        (should= false (:verified? embedded))
        (should= (manifest/module-hash analysis-content) (:module-hash embedded))
        (should= (manifest/top-level-form-manifest analysis-content) (:forms embedded)))
      (.delete temp-file))))

(describe "line numbers stable across stamp"
  (it "reported survivor lines from full run work with --lines"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          original "(ns test-ns)\n(defn foo [] (+ 1 2))\n"]
      (spit temp-path original)
      (with-redefs [runner/run-specs (fn [& _] :survived)
                    runner/run-specs-timed (fn [cmd]
                                             (should= (cli/default-test-command) cmd)
                                             {:result :survived :elapsed-ms 100})
                    coverage/load-coverage (fn [& _] nil)
                    execution/run-mutations-parallel
                    (fn [sites source-path content timeout-ms max-workers test-command & _]
                      (should= nil max-workers)
                      (should= (cli/default-test-command) test-command)
                      (let [results (doall (map-indexed
                                             (fn [i site]
                                               (let [r (execution/mutate-and-test source-path content nil site timeout-ms test-command)]
                                                 (report/print-progress i (count sites) r site)
                                                 r))
                                             sites))]
                        results))]
        (let [output (with-out-str
                       (workflow/run-mutation-testing temp-path))
              plus-match (re-find #"(\d+):\d+\s+\+ -> -" output)
              reported-line (when plus-match (parse-long (second plus-match)))]
          (should-not-be-nil reported-line)
          (should= original (slurp temp-path))
          (let [lines-report (with-out-str
                               (workflow/run-mutation-testing temp-path
                                                              #{reported-line}))]
            (should-contain "+ -> -" lines-report)
            (should= original (slurp temp-path)))))
      (.delete temp-file))))

(describe "write-manifest?"
  (it "writes only after a complete kill with no uncovered sites"
    (let [killed [{:result :killed}]
          survived [{:result :survived}]]
      (should (workflow/write-manifest? nil [{:index 0}] killed []))
      (should-not (workflow/write-manifest? #{2} [{:index 0}] killed []))
      (should-not (workflow/write-manifest? nil [] killed []))
      (should-not (workflow/write-manifest? nil [{:index 0}] survived []))
      (should-not (workflow/write-manifest? nil [{:index 0}] killed [{:line 9}])))))

(describe "run-mutation-testing first run reporting"
  (it "prints uncovered sites when no footer exists"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          original "(ns test-ns)\n(defn foo [] (+ 1 2))\n(defn bar [] (> x 0))\n"]
      (spit temp-path original)
      (with-redefs [runner/run-specs (fn [& _] :killed)
                    runner/run-specs-timed (fn [_] {:result :survived :elapsed-ms 100})
                    coverage/load-coverage (fn [& _] #{2})
                    execution/run-mutations-parallel
                    (fn [sites _ _ _ _ _ & _]
                      (mapv (fn [site] {:site site :result :killed :timeout? false}) sites))]
        (let [output (with-out-str
                       (workflow/run-mutation-testing temp-path))]
          (should-contain "Coverage Gaps" output)
          (should= original (slurp temp-path))))
      (.delete temp-file)))

  (it "backs up the original file contents, not the next footer"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          original "(ns test-ns)\n(defn foo [] (+ 1 2))\n"
          captured (atom nil)]
      (spit temp-path original)
      (with-redefs [runner/run-specs (fn [& _] :killed)
                    runner/run-specs-timed (fn [_] {:result :survived :elapsed-ms 100})
                    coverage/load-coverage (fn [& _] nil)
                    backup/save-backup! (fn [_ content] (reset! captured content))
                    execution/run-mutations-parallel
                    (fn [sites _ _ _ _ _ & _]
                      (mapv (fn [site] {:site site :result :killed :timeout? false}) sites))]
        (workflow/run-mutation-testing temp-path)
        (should= original @captured)
        (should-not-contain "clj-mutate-manifest-begin" @captured))
      (.delete temp-file))))

(run-specs)
