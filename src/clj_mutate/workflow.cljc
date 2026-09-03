(ns clj-mutate.workflow
  (:require [clj-mutate.backup :as backup]
            [clj-mutate.coverage :as coverage]
            [clj-mutate.execution :as execution]
            [clj-mutate.manifest :as manifest]
            [clj-mutate.project :as project]
            [clj-mutate.report :as report]
            [clj-mutate.runner :as runner]
            [clj-mutate.selection :as selection]
            [clj-mutate.source :as source]))

(defn mutation-run-context
  [source-path since-last-run reuse-lcov]
  (let [original-content (slurp source-path)
        prior-manifest (manifest/extract-embedded-manifest original-content)
        manifest-exists? (some? prior-manifest)
        analysis-content (manifest/strip-mutation-metadata original-content)
        forms (source/read-source-forms analysis-content)
        current-module-hash (manifest/module-hash forms)
        module-unchanged? (and since-last-run
                               prior-manifest
                               (= current-module-hash (:module-hash prior-manifest)))
        module-hash-changed? (when manifest-exists?
                               (not= current-module-hash (:module-hash prior-manifest)))
        by-reason (when prior-manifest
                    (manifest/changed-form-indices-by-reason forms prior-manifest))
        new-form-indices (or (:new-form-indices by-reason) #{})
        manifest-violating-form-indices (or (:manifest-violating-form-indices by-reason) #{})
        changed-form-indices (cond
                               (not since-last-run) nil
                               module-unchanged? #{}
                               :else (:changed-form-indices by-reason))
        all-sites (source/discover-all-mutations forms)
        coverage-status (coverage/coverage-status source-path)
        covered-lines (coverage/load-coverage source-path {:reuse-lcov reuse-lcov})
        [covered-sites uncovered] (source/partition-by-coverage all-sites covered-lines)
        changed-mutation-sites (selection/count-changed-sites all-sites prior-manifest forms)
        surface-counts (if manifest-exists?
                         (selection/differential-site-counts all-sites new-form-indices manifest-violating-form-indices)
                         {:new-form-mutations (count all-sites)
                          :manifest-violating-form-mutations 0})]
    {:original-content original-content
     :prev-date (manifest/extract-mutation-date original-content)
     :prior-manifest prior-manifest
     :manifest-exists? manifest-exists?
     :analysis-content analysis-content
     :forms forms
     :module-unchanged? module-unchanged?
     :module-hash-changed? module-hash-changed?
     :reuse-lcov reuse-lcov
     :coverage-status coverage-status
     :new-form-indices new-form-indices
     :manifest-violating-form-indices manifest-violating-form-indices
     :all-sites all-sites
     :covered-sites covered-sites
     :uncovered uncovered
     :changed-mutation-sites changed-mutation-sites
     :surface-area-counts surface-counts
     :manifest-content (manifest/embed-mutation-manifest analysis-content
                                                        (manifest/build-embedded-manifest forms (manifest/now-str)))
     :changed-forms changed-form-indices}))

(defn scan-mutation-sites
  [source-path mutation-warning]
  (let [content (slurp source-path)
        prev-date (manifest/extract-mutation-date content)
        prior-manifest (manifest/extract-embedded-manifest content)
        analysis-content (manifest/strip-mutation-metadata content)
        forms (source/read-source-forms analysis-content)
        all-sites (source/discover-all-mutations forms)
        changed-sites (selection/count-changed-sites all-sites prior-manifest forms)]
    (report/print-scan-report source-path prev-date (count all-sites) changed-sites mutation-warning)))

(defn run-mutation-suite
  [sites source-path analysis-content timeout-ms max-workers test-command]
  (if (seq sites)
    (execution/run-mutations-parallel sites source-path analysis-content timeout-ms max-workers test-command report/print-progress)
    []))

(defn with-baseline
  [test-command timeout-factor on-pass]
  (report/print-baseline-start)
  (let [{baseline-result :result elapsed-ms :elapsed-ms} (runner/run-specs-timed test-command)
        timeout-ms (* timeout-factor elapsed-ms)]
    (if (= :survived baseline-result)
      (do
        (report/print-baseline-pass elapsed-ms timeout-ms)
        (on-pass timeout-ms))
      (report/print-baseline-fail))))

(defn update-manifest!
  [source-path]
  (when (backup/restore-from-backup! source-path)
    (report/print-backup-restored))
  (let [content (slurp source-path)
        analysis-content (manifest/strip-mutation-metadata content)
        forms (source/read-source-forms analysis-content)
        manifest-content (manifest/embed-mutation-manifest
                           analysis-content
                           (manifest/build-embedded-manifest forms (manifest/now-str)))]
    (spit source-path manifest-content)
    (report/print-manifest-updated source-path)))

(defn write-manifest?
  "True when this run fully tested the selected covered sites with no survivors
   and no uncovered sites remaining. --lines reruns and empty runs must not
   stamp a new baseline."
  [lines sites results uncovered]
  (and (nil? lines)
       (seq sites)
       (every? #(= :killed (:result %)) results)
       (empty? uncovered)))

(defn run-mutation-testing
  ([source-path] (run-mutation-testing source-path nil 10 (project/default-test-command) nil false false 100 false))
  ([source-path lines] (run-mutation-testing source-path lines 10 (project/default-test-command) nil false false 100 false))
  ([source-path lines timeout-factor test-command max-workers]
   (run-mutation-testing source-path lines timeout-factor test-command max-workers false false 100 false))
  ([source-path lines timeout-factor test-command max-workers since-last-run]
   (run-mutation-testing source-path lines timeout-factor test-command max-workers since-last-run false 100 false))
  ([source-path lines timeout-factor test-command max-workers since-last-run mutate-all mutation-warning]
   (run-mutation-testing source-path lines timeout-factor test-command max-workers since-last-run mutate-all mutation-warning false))
  ([source-path lines timeout-factor test-command max-workers since-last-run mutate-all mutation-warning reuse-lcov]
   (when (backup/restore-from-backup! source-path)
     (report/print-backup-restored))
   (let [prior-manifest-or-nil (manifest/extract-embedded-manifest (slurp source-path))
         effective-since-last-run (selection/default-since-last-run? lines since-last-run mutate-all prior-manifest-or-nil)
         {:keys [prev-date prior-manifest analysis-content all-sites covered-sites uncovered
                 module-unchanged? changed-forms manifest-content original-content
                 manifest-exists? module-hash-changed? changed-mutation-sites surface-area-counts
                 coverage-status]}
         (mutation-run-context source-path effective-since-last-run reuse-lcov)
         sites (selection/select-mutation-sites covered-sites lines effective-since-last-run module-unchanged? changed-forms)]
     (report/print-run-header source-path prev-date {:all-sites all-sites
                                                     :covered-sites covered-sites
                                                     :uncovered uncovered
                                                     :changed-mutation-sites changed-mutation-sites
                                                     :manifest-exists? manifest-exists?
                                                     :module-hash-changed? module-hash-changed?
                                                     :reuse-lcov reuse-lcov
                                                     :coverage-status coverage-status
                                                     :surface-area-counts surface-area-counts}
                              lines effective-since-last-run prior-manifest module-unchanged? sites mutation-warning)
     (with-baseline
       test-command
       timeout-factor
       (fn [timeout-ms]
         (when-not (or lines effective-since-last-run)
           (report/print-uncovered uncovered))
         (when original-content
           (backup/save-backup! source-path original-content))
         (try
           (let [results (run-mutation-suite sites source-path analysis-content timeout-ms max-workers test-command)]
             (report/summarize-results results
                                       lines
                                       effective-since-last-run
                                       uncovered)
             (when (write-manifest? lines sites results uncovered)
               (spit source-path manifest-content)))
           (finally
             (backup/cleanup-backup! source-path))))))))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-09-02T15:18:25.990555-05:00", :module-hash "-887927905", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line nil, :hash "1654870243"} {:id "defn/mutation-run-context", :kind "defn", :line 12, :end-line nil, :hash "-1469696740"} {:id "defn/scan-mutation-sites", :kind "defn", :line 60, :end-line nil, :hash "792242941"} {:id "defn/run-mutation-suite", :kind "defn", :line 71, :end-line nil, :hash "-1773928310"} {:id "defn/with-baseline", :kind "defn", :line 77, :end-line nil, :hash "1734598447"} {:id "defn/update-manifest!", :kind "defn", :line 88, :end-line nil, :hash "-685529017"} {:id "defn/run-mutation-testing", :kind "defn", :line 101, :end-line nil, :hash "1839474640"}]}
;; clj-mutate-manifest-end
