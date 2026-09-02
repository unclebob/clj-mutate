(ns clj-mutate.workflow
  (:require [clojure.string :as str]
            [clj-mutate.coverage :as coverage]
            [clj-mutate.execution :as execution]
            [clj-mutate.manifest :as manifest]
            [clj-mutate.runner :as runner]
            [clj-mutate.source :as source]))

(declare count-changed-sites
         differential-site-counts)

(defn- filter-sites-by
  [sites allowed-values key-fn]
  (if allowed-values
    (vec (filter #(contains? allowed-values (key-fn %)) sites))
    sites))

(defn filter-by-lines
  [sites lines]
  (filter-sites-by sites lines :line))

(defn filter-by-form-indices
  [sites form-indices]
  (filter-sites-by sites form-indices :form-index))

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
        module-hash-changed? (when manifest-exists? (not module-unchanged?))
        {:keys [new-form-indices manifest-violating-form-indices changed-form-indices]}
        (if (and since-last-run prior-manifest (not module-unchanged?))
          (manifest/changed-form-indices-by-reason forms prior-manifest)
          {:new-form-indices #{}
           :manifest-violating-form-indices #{}
           :changed-form-indices nil})
        all-sites (source/discover-all-mutations forms)
        coverage-status (coverage/coverage-status source-path)
        covered-lines (coverage/load-coverage source-path {:reuse-lcov reuse-lcov})
        [covered-sites uncovered] (source/partition-by-coverage all-sites covered-lines)
        changed-mutation-sites (count-changed-sites all-sites prior-manifest forms)
        surface-counts (if manifest-exists?
                         (differential-site-counts all-sites new-form-indices manifest-violating-form-indices)
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
     :sites nil
     :manifest-content (manifest/embed-mutation-manifest analysis-content
                                                        (manifest/build-embedded-manifest forms (manifest/now-str)))
     :changed-forms changed-form-indices}))

(defn default-since-last-run?
  [lines since-last-run mutate-all prior-manifest]
  (and (nil? lines)
       (not mutate-all)
       (or since-last-run (some? prior-manifest))))

(defn select-mutation-sites
  [covered-sites lines since-last-run module-unchanged? changed-forms]
  (cond
    lines (filter-by-lines covered-sites lines)
    module-unchanged? []
    since-last-run (filter-by-form-indices covered-sites changed-forms)
    :else covered-sites))

(defn print-mutation-warning
  [warning-threshold total-mutations]
  (when (> total-mutations warning-threshold)
    (println (format "WARNING: Found %d mutations. Consider splitting this module." total-mutations))))

(defn- count-changed-sites
  [all-sites prior-manifest forms]
  (cond
    (nil? prior-manifest) (count all-sites)
    (= (:module-hash prior-manifest) (manifest/module-hash forms)) 0
    :else (count (filter-by-form-indices all-sites (manifest/changed-form-indices forms prior-manifest)))))

(defn- print-previous-mutation-test
  [prev-date]
  (when prev-date
    (println (format "Previous mutation test: %s" prev-date))))

(defn scan-mutation-sites
  [source-path mutation-warning]
  (let [content (slurp source-path)
        prev-date (manifest/extract-mutation-date content)
        prior-manifest (manifest/extract-embedded-manifest content)
        analysis-content (manifest/strip-mutation-metadata content)
        forms (source/read-source-forms analysis-content)
        all-sites (source/discover-all-mutations forms)
        changed-sites (count-changed-sites all-sites prior-manifest forms)]
    (println (format "=== Mutation Scan: %s ===" source-path))
    (print-previous-mutation-test prev-date)
    (println (format "Found %d mutation sites." (count all-sites)))
    (println (format "Changed mutation sites: %d" changed-sites))
    (print-mutation-warning mutation-warning (count all-sites))))

(defn- print-reuse-lcov-status
  [reuse-lcov coverage-status]
  (when reuse-lcov
    (println (format "Reusing existing LCOV data from %s."
                     (:lcov-path coverage-status)))
    (println "Warning: coverage may be stale; covered/uncovered site classification may be inaccurate.")
    (println (format "LCOV exists: %s" (if (:exists? coverage-status) "yes" "no")))
    (when (:last-modified coverage-status)
      (println (format "LCOV last modified: %d" (:last-modified coverage-status))))
    (println (format "Target source newer than LCOV: %s"
                     (if (:source-newer? coverage-status) "yes" "no")))))

(defn- print-site-counts
  [all-sites covered-sites uncovered changed-mutation-sites]
  (println (format "Total mutation sites: %d" (count all-sites)))
  (println (format "Covered mutation sites: %d" (count covered-sites)))
  (println (format "Uncovered mutation sites: %d" (count uncovered)))
  (println (format "Changed mutation sites: %d" changed-mutation-sites)))

(defn- print-manifest-status
  [manifest-exists? module-hash-changed?]
  (println (format "Manifest exists: %s" (if manifest-exists? "yes" "no")))
  (println (format "Module hash changed: %s"
                   (if manifest-exists?
                     (if module-hash-changed? "yes" "no")
                     "n/a"))))

(defn- print-surface-area
  [surface-counts]
  (println (format "Differential surface area: %d mutations in new top-level forms"
                   (:new-form-mutations surface-counts)))
  (println (format "Manifest-violating surface area: %d mutations"
                   (:manifest-violating-form-mutations surface-counts))))

(defn- print-line-filter
  [lines sites]
  (when lines
    (println (format "Filtering to lines: %s → %d mutations to test."
                     (str/join "," (sort lines)) (count sites)))))

(defn- print-since-last-run-filter
  [since-last-run prior-manifest module-unchanged? sites]
  (when since-last-run
    (if prior-manifest
      (if module-unchanged?
        (println "Module hash unchanged; no mutations to test.")
        (println (format "Filtering to changed top-level forms → %d mutations to test."
                         (count sites))))
      (println "No prior embedded manifest found; running all covered mutations."))))

(defn print-run-header
  [source-path prev-date header-info lines since-last-run prior-manifest module-unchanged? sites warning-threshold]
  (let [{:keys [all-sites covered-sites uncovered changed-mutation-sites
                manifest-exists? module-hash-changed? reuse-lcov coverage-status]
         :as info} header-info
        surface-counts (:surface-area-counts info)]
    (println (format "=== Mutation Testing: %s ===" source-path))
    (print-previous-mutation-test prev-date)
    (print-reuse-lcov-status reuse-lcov coverage-status)
    (print-site-counts all-sites covered-sites uncovered changed-mutation-sites)
    (print-manifest-status manifest-exists? module-hash-changed?)
    (print-surface-area surface-counts)
    (print-mutation-warning warning-threshold (count all-sites))
    (print-line-filter lines sites)
    (print-since-last-run-filter since-last-run prior-manifest module-unchanged? sites)
    (println)))

(defn print-uncovered
  [uncovered]
  (when (seq uncovered)
    (println (format "\n=== Coverage Gaps (%d mutations on uncovered lines) ==="
                     (count uncovered)))
    (doseq [site uncovered]
      (println (format "  line %d: %s" (:line site) (:description site))))))

(defn differential-site-counts
  [sites new-form-indices manifest-violating-form-indices]
  {:new-form-mutations (count (filter #(contains? new-form-indices (:form-index %)) sites))
   :manifest-violating-form-mutations (count (filter #(contains? manifest-violating-form-indices (:form-index %)) sites))})

(defn- print-summary
  [killed total pct survivors uncovered-count]
  (println (format "\n=== Summary ==="))
  (println (format "%d/%d mutants killed (%.1f%%)" killed total pct))
  (when (pos? uncovered-count)
    (println (format "%d uncovered mutations skipped" uncovered-count)))
  (when (seq survivors)
    (println "Survivors:")
    (doseq [r survivors]
      (println (format "  #%d  L%-4d %s"
                       (inc (:index (:site r)))
                       (or (:line (:site r)) 0)
                       (:description (:site r)))))))

(defn summarize-results
  [results lines since-last-run uncovered _differential-counts]
  (let [killed (count (filter #(= :killed (:result %)) results))
        total (count results)
        pct (if (zero? total) 0.0 (* 100.0 (/ killed total)))
        survivors (filter #(= :survived (:result %)) results)]
    (print-summary killed total pct survivors (if (or lines since-last-run) 0 (count uncovered)))))

(defn run-mutation-suite
  [sites source-path analysis-content timeout-ms max-workers test-command]
  (if (seq sites)
    (execution/run-mutations-parallel sites source-path analysis-content timeout-ms max-workers test-command)
    []))

(defn with-baseline
  [test-command timeout-factor on-pass]
  (print "Baseline: ")
  (flush)
  (let [{baseline-result :result elapsed-ms :elapsed-ms} (runner/run-specs-timed test-command)
        timeout-ms (* timeout-factor elapsed-ms)]
    (if (= :survived baseline-result)
      (do
        (println (format "PASS (%.1fs, timeout %.1fs)"
                         (/ elapsed-ms 1000.0) (/ timeout-ms 1000.0)))
        (on-pass timeout-ms))
      (println "FAIL — specs do not pass without mutations. Aborting."))))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-09-02T14:42:55.941437-05:00", :module-hash "-661820954", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line 7, :hash "673503783"} {:id "form/1/declare", :kind "declare", :line 9, :end-line 10, :hash "1862395422"} {:id "defn-/filter-sites-by", :kind "defn-", :line 12, :end-line 16, :hash "-2118914095"} {:id "defn/filter-by-lines", :kind "defn", :line 18, :end-line 20, :hash "-479853682"} {:id "defn/filter-by-form-indices", :kind "defn", :line 22, :end-line 24, :hash "-1270395709"} {:id "defn/mutation-run-context", :kind "defn", :line 26, :end-line 73, :hash "65925430"} {:id "defn/default-since-last-run?", :kind "defn", :line 75, :end-line 79, :hash "-71751391"} {:id "defn/select-mutation-sites", :kind "defn", :line 81, :end-line 87, :hash "-1457564386"} {:id "defn/print-mutation-warning", :kind "defn", :line 89, :end-line 92, :hash "-8091185"} {:id "defn-/count-changed-sites", :kind "defn-", :line 94, :end-line 99, :hash "2117283179"} {:id "defn-/print-previous-mutation-test", :kind "defn-", :line 101, :end-line 104, :hash "1791720437"} {:id "defn/scan-mutation-sites", :kind "defn", :line 106, :end-line 119, :hash "-1244175502"} {:id "defn-/print-reuse-lcov-status", :kind "defn-", :line 121, :end-line 131, :hash "245360152"} {:id "defn-/print-site-counts", :kind "defn-", :line 133, :end-line 138, :hash "-668192430"} {:id "defn-/print-manifest-status", :kind "defn-", :line 140, :end-line 146, :hash "1457750507"} {:id "defn-/print-surface-area", :kind "defn-", :line 148, :end-line 153, :hash "1682025556"} {:id "defn-/print-line-filter", :kind "defn-", :line 155, :end-line 159, :hash "1854309138"} {:id "defn-/print-since-last-run-filter", :kind "defn-", :line 161, :end-line 169, :hash "1987635254"} {:id "defn/print-run-header", :kind "defn", :line 171, :end-line 186, :hash "-665253061"} {:id "defn/print-uncovered", :kind "defn", :line 188, :end-line 194, :hash "-1174101582"} {:id "defn/differential-site-counts", :kind "defn", :line 196, :end-line 199, :hash "688494479"} {:id "defn-/print-summary", :kind "defn-", :line 201, :end-line 213, :hash "-647776276"} {:id "defn/summarize-results", :kind "defn", :line 215, :end-line 221, :hash "-895382669"} {:id "defn/run-mutation-suite", :kind "defn", :line 223, :end-line 227, :hash "307720017"} {:id "defn/with-baseline", :kind "defn", :line 229, :end-line 240, :hash "2111533211"}]}
;; clj-mutate-manifest-end
