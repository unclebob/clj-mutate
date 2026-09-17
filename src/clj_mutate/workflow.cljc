(ns clj-mutate.workflow
  (:require [clj-mutate.backup :as backup]
            [clj-mutate.coverage :as coverage]
            [clj-mutate.execution :as execution]
            [clj-mutate.manifest :as manifest]
            [clj-mutate.project :as project]
            [clj-mutate.report :as report]
            [clj-mutate.runner :as runner]
            [clj-mutate.selection :as selection]
            [clj-mutate.snapshot :as snapshot]
            [clj-mutate.source :as source]))

(defn- normalize-context-options
  [options-or-reuse]
  (if (map? options-or-reuse)
    options-or-reuse
    {:reuse-lcov options-or-reuse
     :test-command (project/default-test-command)
     :coverage-command (project/default-coverage-command)}))

(defn mutation-run-context
  "Plan a run, then load coverage only when the plan has work. The third
   argument may be the legacy reuse-lcov boolean or an options map."
  [source-path since-last-run options-or-reuse]
  (let [{:keys [reuse-lcov test-command coverage-command test-roots]}
        (normalize-context-options options-or-reuse)
        original-content (slurp source-path)
        prior-manifest (snapshot/load-prior source-path original-content)
        manifest-exists? (some? prior-manifest)
        analysis-content (manifest/strip-mutation-metadata original-content)
        current-module-hash (manifest/module-hash analysis-content)
        usable? (snapshot/usable-snapshot? prior-manifest)
        same-module? (and usable?
                          (= current-module-hash (:module-hash prior-manifest)))
        by-reason (when usable?
                    (manifest/changed-form-indices-by-reason analysis-content prior-manifest))
        all-sites (source/discover-mutations analysis-content)
        retry-sites (if (and since-last-run usable?)
                      (snapshot/sites-to-retry all-sites prior-manifest analysis-content)
                      all-sites)
        skip-module? (and since-last-run same-module? (empty? retry-sites))
        new-form-indices (or (:new-form-indices by-reason) #{})
        rewritten-form-indices (or (:manifest-violating-form-indices by-reason) #{})
        surface-counts {:new-form-mutations
                        (count (selection/filter-by-form-indices all-sites new-form-indices))
                        :manifest-violating-form-mutations
                        (count (selection/filter-by-form-indices
                                 all-sites rewritten-form-indices))}
        base-context
        {:original-content original-content
         :prev-date (or (:tested-at prior-manifest)
                        (manifest/extract-mutation-date original-content))
         :prior-manifest prior-manifest
         :manifest-exists? manifest-exists?
         :analysis-content analysis-content
         :same-module? same-module?
         :skip-module? skip-module?
         :module-hash-changed? (when manifest-exists? (not same-module?))
         :reuse-lcov reuse-lcov
         :test-roots (project/test-profile-roots
                       (System/getProperty "user.dir") test-command test-roots)
         :new-form-indices new-form-indices
         :manifest-violating-form-indices rewritten-form-indices
         :all-sites all-sites
         :retry-sites retry-sites
         :changed-mutation-sites (count retry-sites)
         :surface-area-counts surface-counts}]
    (if skip-module?
      (assoc base-context
             :covered-sites []
             :uncovered []
             :coverage-status {:status :not-loaded})
      (let [loaded (coverage/load-coverage
                     source-path {:reuse-lcov reuse-lcov
                                  :coverage-command coverage-command
                                  :test-command test-command
                                  :test-roots test-roots})
            coverage-data (if (map? loaded)
                            loaded
                            {:lines loaded :status :unavailable})
            retry-set (into #{} (map :mutation-id retry-sites))
            [covered-all uncovered-all]
            (source/partition-by-coverage all-sites (:lines coverage-data))
            covered-sites (if (and since-last-run usable?)
                            (filterv #(contains? retry-set (:mutation-id %)) covered-all)
                            covered-all)
            uncovered (if (and since-last-run usable?)
                        (filterv #(contains? retry-set (:mutation-id %)) uncovered-all)
                        uncovered-all)]
        (assoc base-context
               :covered-sites covered-sites
               :uncovered uncovered
               :coverage-status (dissoc coverage-data :lines))))))

(defn scan-mutation-sites
  [source-path mutation-warning]
  (let [content (slurp source-path)
        prior-manifest (snapshot/load-prior source-path content)
        prev-date (or (:tested-at prior-manifest)
                      (manifest/extract-mutation-date content))
        analysis-content (manifest/strip-mutation-metadata content)
        all-sites (source/discover-mutations analysis-content)
        retry (snapshot/sites-to-retry all-sites prior-manifest analysis-content)
        changed-sites (if (snapshot/usable-snapshot? prior-manifest)
                        (count retry)
                        (count all-sites))]
    (report/print-scan-report source-path prev-date (count all-sites) changed-sites mutation-warning)
    {:status :passed :mutations (count all-sites)}))

(defn run-mutation-suite
  ([sites source-path analysis-content timeout-ms max-workers test-command]
   (run-mutation-suite sites source-path analysis-content timeout-ms max-workers
                       test-command nil))
  ([sites source-path analysis-content timeout-ms max-workers test-command test-roots]
   (if (seq sites)
     (execution/run-mutations-parallel sites source-path analysis-content timeout-ms
                                       max-workers test-command report/print-progress test-roots)
     [])))

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

(defn- persist-snapshot!
  [source-path analysis-content prior
   {:keys [results uncovered sites]}]
  (let [tested-ids (into #{} (keep :form-id sites))
        snapshot (snapshot/build-snapshot
                   source-path analysis-content (manifest/now-str)
                   {:prior-forms (:forms prior)
                    :prior-outcomes (:outcomes prior)
                    :results (or results [])
                    :uncovered (or uncovered [])
                    :tested-ids tested-ids})]
    (snapshot/write-snapshot! source-path snapshot)
    (snapshot/strip-source-footer! source-path)
    snapshot))

(defn update-manifest!
  "Human override: record every current site as killed without running workers."
  [source-path]
  (when (backup/restore-from-backup! source-path)
    (report/print-backup-restored))
  (let [content (slurp source-path)
        analysis-content (manifest/strip-mutation-metadata content)
        prior (snapshot/load-prior source-path content)
        sites (source/discover-mutations analysis-content)
        results (mapv (fn [site] {:site site :result :killed}) sites)]
    (persist-snapshot! source-path analysis-content prior
                       {:results results :uncovered [] :sites sites})
    (report/print-manifest-updated source-path)
    {:status :passed :manifest-updated? true}))

(defn- scoped-uncovered
  [uncovered lines mutation]
  (cond
    mutation (selection/filter-by-mutation uncovered mutation)
    lines (selection/filter-by-lines uncovered lines)
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
   (run-mutation-testing source-path lines timeout-factor test-command max-workers since-last-run mutate-all mutation-warning reuse-lcov mutation coverage-command nil))
  ([source-path lines timeout-factor test-command max-workers since-last-run mutate-all mutation-warning reuse-lcov mutation coverage-command test-roots]
   (when (backup/restore-from-backup! source-path)
     (report/print-backup-restored))
   (let [prior-manifest-or-nil (snapshot/load-prior source-path (slurp source-path))
         effective-since-last-run
         (and (nil? mutation)
              (selection/default-since-last-run?
                lines since-last-run mutate-all prior-manifest-or-nil))
         context
         (mutation-run-context source-path effective-since-last-run
                               {:reuse-lcov reuse-lcov
                                :test-command test-command
                                :coverage-command coverage-command
                                :test-roots test-roots})
         {:keys [prev-date prior-manifest analysis-content all-sites covered-sites uncovered
                 skip-module? original-content test-roots
                 manifest-exists? module-hash-changed? changed-mutation-sites
                 surface-area-counts coverage-status]} context]
     (if skip-module?
       (do
         (report/print-no-changes source-path prev-date)
         {:status :no-changes :mutations 0})
       (let [sites (selection/select-mutation-sites
                     covered-sites lines mutation false false nil)
             in-scope-uncovered (scoped-uncovered uncovered lines mutation)
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
             (persist-snapshot! source-path analysis-content prior-manifest
                                {:results []
                                 :uncovered in-scope-uncovered
                                 :sites sites})
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
                                           timeout-ms max-workers test-command
                                           test-roots)
                       status (result-status results in-scope-uncovered)
                       summary (report/summarize-results
                                 results lines effective-since-last-run in-scope-uncovered)]
                   (persist-snapshot! source-path analysis-content prior-manifest
                                      {:results results
                                       :uncovered in-scope-uncovered
                                       :sites sites})
                   (merge summary {:status status}))
                 (finally
                   (backup/cleanup-backup! source-path)))))))))))
