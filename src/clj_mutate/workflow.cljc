(ns clj-mutate.workflow
  (:require [clj-mutate.backup :as backup]
            [clj-mutate.coverage :as coverage]
            [clj-mutate.execution :as execution]
            [clj-mutate.manifest :as manifest]
            [clj-mutate.mutations :as mutations]
            [clj-mutate.project :as project]
            [clj-mutate.report :as report]
            [clj-mutate.runner :as runner]
            [clj-mutate.selection :as selection]
            [clj-mutate.source :as source]))

(defn mutation-provenance
  [test-command]
  {:mutation-rules-version mutations/rules-version
   :test-command test-command
   :test-profile-fingerprint (project/test-profile-fingerprint test-command)})

(defn- normalize-context-options
  [options-or-reuse]
  (if (map? options-or-reuse)
    options-or-reuse
    {:reuse-lcov options-or-reuse
     :test-command (project/default-test-command)
     :coverage-command (project/default-coverage-command)}))

(defn- all-form-indices
  [analysis-content]
  (set (range (count (manifest/top-level-form-manifest analysis-content)))))

(defn mutation-run-context
  "Plan a run, then load coverage only when the plan has work. The third
   argument may be the legacy reuse-lcov boolean or an options map."
  [source-path since-last-run options-or-reuse]
  (let [{:keys [reuse-lcov test-command coverage-command]}
        (normalize-context-options options-or-reuse)
        original-content (slurp source-path)
        prior-manifest (manifest/extract-embedded-manifest original-content)
        manifest-exists? (some? prior-manifest)
        analysis-content (manifest/strip-mutation-metadata original-content)
        provenance (mutation-provenance test-command)
        current-module-hash (manifest/module-hash analysis-content)
        trusted-manifest? (manifest/trusted-manifest? prior-manifest provenance)
        same-module? (= current-module-hash (:module-hash prior-manifest))
        module-unchanged? (and since-last-run trusted-manifest? same-module?)
        module-hash-changed? (when manifest-exists? (not same-module?))
        by-reason (when (and trusted-manifest? (not same-module?))
                    (manifest/changed-form-indices-by-reason analysis-content prior-manifest))
        every-form (all-form-indices analysis-content)
        new-form-indices (if trusted-manifest?
                           (or (:new-form-indices by-reason) #{})
                           (if manifest-exists? #{} every-form))
        manifest-violating-form-indices
        (if trusted-manifest?
          (or (:manifest-violating-form-indices by-reason) #{})
          (if manifest-exists? every-form #{}))
        changed-form-indices (cond
                               (not since-last-run) nil
                               module-unchanged? #{}
                               trusted-manifest? (:changed-form-indices by-reason)
                               :else every-form)
        all-sites (source/discover-mutations analysis-content)
        changed-mutation-sites
        (cond
          (not trusted-manifest?) (count all-sites)
          same-module? 0
          :else (count (selection/filter-by-form-indices
                         all-sites (:changed-form-indices by-reason))))
        surface-counts {:new-form-mutations
                        (count (selection/filter-by-form-indices all-sites new-form-indices))
                        :manifest-violating-form-mutations
                        (count (selection/filter-by-form-indices
                                 all-sites manifest-violating-form-indices))}
        base-context
        {:original-content original-content
         :prev-date (manifest/extract-mutation-date original-content)
         :prior-manifest prior-manifest
         :manifest-exists? manifest-exists?
         :trusted-manifest? trusted-manifest?
         :analysis-content analysis-content
         :module-unchanged? module-unchanged?
         :module-hash-changed? module-hash-changed?
         :reuse-lcov reuse-lcov
         :new-form-indices new-form-indices
         :manifest-violating-form-indices manifest-violating-form-indices
         :all-sites all-sites
         :changed-mutation-sites changed-mutation-sites
         :surface-area-counts surface-counts
         :changed-forms changed-form-indices
         :provenance provenance
         :manifest-content
         (manifest/embed-mutation-manifest
           analysis-content
           (manifest/build-embedded-manifest
             analysis-content (manifest/now-str)
             {:verified? true :provenance provenance}))}]
    (if module-unchanged?
      (assoc base-context
             :covered-sites []
             :uncovered []
             :coverage-status {:status :not-loaded})
      (let [loaded (coverage/load-coverage
                     source-path {:reuse-lcov reuse-lcov
                                  :coverage-command coverage-command
                                  :test-command test-command})
            coverage-data (if (map? loaded)
                            loaded
                            {:lines loaded :status :unavailable})
            [covered-sites uncovered]
            (source/partition-by-coverage all-sites (:lines coverage-data))]
        (assoc base-context
               :covered-sites covered-sites
               :uncovered uncovered
               :coverage-status (dissoc coverage-data :lines))))))

(defn scan-mutation-sites
  [source-path mutation-warning]
  (let [content (slurp source-path)
        prev-date (manifest/extract-mutation-date content)
        prior-manifest (manifest/extract-embedded-manifest content)
        analysis-content (manifest/strip-mutation-metadata content)
        all-sites (source/discover-mutations analysis-content)
        changed-sites (selection/count-changed-sites all-sites prior-manifest analysis-content)]
    (report/print-scan-report source-path prev-date (count all-sites) changed-sites mutation-warning)
    {:status :passed :mutations (count all-sites)}))

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
      (do
        (report/print-baseline-fail)
        {:status :baseline-failed}))))

(defn update-manifest!
  [source-path]
  (when (backup/restore-from-backup! source-path)
    (report/print-backup-restored))
  (let [content (slurp source-path)
        analysis-content (manifest/strip-mutation-metadata content)
        provenance (mutation-provenance (project/default-test-command))
        manifest-content
        (manifest/embed-mutation-manifest
          analysis-content
          (manifest/build-embedded-manifest
            analysis-content (manifest/now-str)
            {:verified? false :provenance provenance}))]
    (spit source-path manifest-content)
    (report/print-manifest-updated source-path)
    {:status :passed :manifest-updated? true}))

(defn write-manifest?
  "True only after an unfiltered-by-site run killed every in-scope covered
   mutation and left no in-scope uncovered mutations."
  ([lines sites results uncovered]
   (write-manifest? lines nil false sites results uncovered))
  ([lines mutation _since-last-run sites results uncovered]
   (and (nil? lines)
        (nil? mutation)
        (seq sites)
        (every? #(= :killed (:result %)) results)
        (empty? uncovered))))

(defn- scoped-uncovered
  [uncovered lines mutation since-last-run changed-forms]
  (cond
    mutation (selection/filter-by-mutation uncovered mutation)
    lines (selection/filter-by-lines uncovered lines)
    since-last-run (selection/filter-by-form-indices uncovered changed-forms)
    :else uncovered))

(defn- result-status
  [results uncovered]
  (cond
    (seq (filter #(= :survived (:result %)) results)) :survivors
    (seq uncovered) :uncovered
    :else :passed))

(defn- executable-coverage?
  [coverage-status]
  (not (contains? #{:refresh-failed :missing} (:status coverage-status))))

(defn run-mutation-testing
  ([source-path]
   (run-mutation-testing source-path nil 10 (project/default-test-command) nil false false 100 false nil (project/default-coverage-command)))
  ([source-path lines]
   (run-mutation-testing source-path lines 10 (project/default-test-command) nil false false 100 false nil (project/default-coverage-command)))
  ([source-path lines timeout-factor test-command max-workers]
   (run-mutation-testing source-path lines timeout-factor test-command max-workers false false 100 false nil (project/default-coverage-command)))
  ([source-path lines timeout-factor test-command max-workers since-last-run]
   (run-mutation-testing source-path lines timeout-factor test-command max-workers since-last-run false 100 false nil (project/default-coverage-command)))
  ([source-path lines timeout-factor test-command max-workers since-last-run mutate-all mutation-warning]
   (run-mutation-testing source-path lines timeout-factor test-command max-workers since-last-run mutate-all mutation-warning false nil (project/default-coverage-command)))
  ([source-path lines timeout-factor test-command max-workers since-last-run mutate-all mutation-warning reuse-lcov]
   (run-mutation-testing source-path lines timeout-factor test-command max-workers since-last-run mutate-all mutation-warning reuse-lcov nil (project/default-coverage-command)))
  ([source-path lines timeout-factor test-command max-workers since-last-run mutate-all mutation-warning reuse-lcov mutation coverage-command]
   (when (backup/restore-from-backup! source-path)
     (report/print-backup-restored))
   (let [prior-manifest-or-nil (manifest/extract-embedded-manifest (slurp source-path))
         effective-since-last-run
         (and (nil? mutation)
              (selection/default-since-last-run?
                lines since-last-run mutate-all prior-manifest-or-nil))
         context
         (mutation-run-context source-path effective-since-last-run
                               {:reuse-lcov reuse-lcov
                                :test-command test-command
                                :coverage-command coverage-command})
         {:keys [prev-date prior-manifest analysis-content all-sites covered-sites uncovered
                 module-unchanged? changed-forms manifest-content original-content
                 manifest-exists? module-hash-changed? changed-mutation-sites
                 surface-area-counts coverage-status]} context]
     (if module-unchanged?
       (do
         (report/print-no-changes source-path prev-date)
         {:status :no-changes :mutations 0})
       (let [sites (selection/select-mutation-sites
                     covered-sites lines mutation effective-since-last-run false changed-forms)
             in-scope-uncovered
             (scoped-uncovered uncovered lines mutation effective-since-last-run changed-forms)
             selector-found? (or (nil? mutation)
                                 (seq (selection/filter-by-mutation all-sites mutation)))]
         (report/print-run-header
           source-path prev-date
           {:all-sites all-sites
            :covered-sites covered-sites
            :uncovered uncovered
            :changed-mutation-sites changed-mutation-sites
            :manifest-exists? manifest-exists?
            :module-hash-changed? module-hash-changed?
            :reuse-lcov reuse-lcov
            :coverage-status coverage-status
            :surface-area-counts surface-area-counts}
           lines mutation effective-since-last-run prior-manifest false sites mutation-warning)
         (cond
           (not selector-found?)
           (do
             (report/print-configuration-error
               (str "No mutation matches " mutation ". Run --scan to inspect the file."))
             {:status :configuration-error :reason :unknown-mutation})

           (not (executable-coverage? coverage-status))
           (do
             (report/print-configuration-error
               "Coverage could not be generated for the requested mutation test profile.")
             {:status :configuration-error :reason (:status coverage-status)})

           (empty? sites)
           (do
             (report/print-uncovered in-scope-uncovered)
             (report/summarize-results [] lines effective-since-last-run in-scope-uncovered)
             {:status (if (seq in-scope-uncovered) :uncovered :passed)
              :mutations 0
              :uncovered (count in-scope-uncovered)})

           :else
           (with-baseline
             test-command timeout-factor
             (fn [timeout-ms]
               (report/print-uncovered in-scope-uncovered)
               (backup/save-backup! source-path original-content)
               (try
                 (let [results
                       (run-mutation-suite sites source-path analysis-content
                                           timeout-ms max-workers test-command)
                       status (result-status results in-scope-uncovered)
                       summary (report/summarize-results
                                 results lines effective-since-last-run in-scope-uncovered)]
                   (when (write-manifest? lines mutation effective-since-last-run
                                          sites results in-scope-uncovered)
                     (spit source-path manifest-content))
                   (merge summary {:status status}))
                 (finally
                   (backup/cleanup-backup! source-path)))))))))))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-09-02T15:18:25.990555-05:00", :module-hash "-887927905", :forms []}
;; clj-mutate-manifest-end
