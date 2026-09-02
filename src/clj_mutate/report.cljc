(ns clj-mutate.report
  (:require [clojure.string :as str]))

(defn print-previous-mutation-test
  [prev-date]
  (when prev-date
    (println (format "Previous mutation test: %s" prev-date))))

(defn print-mutation-warning
  [warning-threshold total-mutations]
  (when (> total-mutations warning-threshold)
    (println (format "WARNING: Found %d mutations. Consider splitting this module." total-mutations))))

(defn print-scan-report
  [source-path prev-date total-sites changed-sites mutation-warning]
  (println (format "=== Mutation Scan: %s ===" source-path))
  (print-previous-mutation-test prev-date)
  (println (format "Found %d mutation sites." total-sites))
  (println (format "Changed mutation sites: %d" changed-sites))
  (print-mutation-warning mutation-warning total-sites))

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
  [results lines since-last-run uncovered]
  (let [killed (count (filter #(= :killed (:result %)) results))
        total (count results)
        pct (if (zero? total) 0.0 (* 100.0 (/ killed total)))
        survivors (filter #(= :survived (:result %)) results)]
    (print-summary killed total pct survivors (if (or lines since-last-run) 0 (count uncovered)))))

(defn- result-label
  [r]
  (cond
    (:timeout? r) "TIMEOUT"
    (= :killed (:result r)) "KILLED"
    :else "SURVIVED"))

(defn- format-line
  [i total r]
  (format "[%3d/%d] %-8s  L%-4d %s%n"
          (inc i) total (result-label r) (or (:line (:site r)) 0) (:description (:site r))))

(defn- format-survivor
  [r]
  (format "  #%d  L%-4d %s%n" (inc (or (:index (:site r)) 0)) (or (:line (:site r)) 0) (:description (:site r))))

(defn format-report
  [source-path results uncovered-count]
  (let [total (count results)
        killed (count (filter #(= :killed (:result %)) results))
        pct (if (zero? total) 0.0 (* 100.0 (/ killed total)))
        survivors (filter #(= :survived (:result %)) results)]
    (str
      (format "=== Mutation Testing: %s ===%n" source-path)
      (format "Found %d mutation sites.%n%n" total)
      (apply str (map-indexed #(format-line %1 total %2) results))
      (format "%n=== Summary ===%n")
      (format "%d/%d mutants killed (%.1f%%)%n" killed total pct)
      (when (pos? uncovered-count)
        (format "%d uncovered mutations skipped%n" uncovered-count))
      (when (seq survivors)
        (str "Survivors:\n"
             (apply str (map format-survivor survivors)))))))

(defn print-progress
  [i total result site]
  (println (format "[%3d/%d] %-8s  L%-4d %s"
                   (inc i) total
                   (result-label result)
                   (or (:line site) 0)
                   (:description site)))
  (flush))

(defn print-baseline-start
  []
  (print "Baseline: ")
  (flush))

(defn print-baseline-pass
  [elapsed-ms timeout-ms]
  (println (format "PASS (%.1fs, timeout %.1fs)"
                   (/ elapsed-ms 1000.0) (/ timeout-ms 1000.0))))

(defn print-baseline-fail
  []
  (println "FAIL — specs do not pass without mutations. Aborting."))

(defn print-backup-restored
  []
  (println "Restored source from backup (previous run was interrupted)."))

(defn print-manifest-updated
  [source-path]
  (println (str "Updated manifest: " source-path)))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-09-02T15:18:37.279832-05:00", :module-hash "144987029", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line nil, :hash "609215190"} {:id "defn/print-previous-mutation-test", :kind "defn", :line 4, :end-line nil, :hash "1006989162"} {:id "defn/print-mutation-warning", :kind "defn", :line 9, :end-line nil, :hash "-8091185"} {:id "defn/print-scan-report", :kind "defn", :line 14, :end-line nil, :hash "1501487419"} {:id "defn-/print-reuse-lcov-status", :kind "defn-", :line 22, :end-line nil, :hash "245360152"} {:id "defn-/print-site-counts", :kind "defn-", :line 34, :end-line nil, :hash "-668192430"} {:id "defn-/print-manifest-status", :kind "defn-", :line 41, :end-line nil, :hash "1457750507"} {:id "defn-/print-surface-area", :kind "defn-", :line 49, :end-line nil, :hash "1682025556"} {:id "defn-/print-line-filter", :kind "defn-", :line 56, :end-line nil, :hash "1854309138"} {:id "defn-/print-since-last-run-filter", :kind "defn-", :line 62, :end-line nil, :hash "1987635254"} {:id "defn/print-run-header", :kind "defn", :line 72, :end-line nil, :hash "-665253061"} {:id "defn/print-uncovered", :kind "defn", :line 89, :end-line nil, :hash "-1174101582"} {:id "defn-/print-summary", :kind "defn-", :line 97, :end-line nil, :hash "-647776276"} {:id "defn/summarize-results", :kind "defn", :line 111, :end-line nil, :hash "189360272"} {:id "defn-/result-label", :kind "defn-", :line 119, :end-line nil, :hash "-1992427708"} {:id "defn-/format-line", :kind "defn-", :line 126, :end-line nil, :hash "-1409718304"} {:id "defn-/format-survivor", :kind "defn-", :line 131, :end-line nil, :hash "791497139"} {:id "defn/format-report", :kind "defn", :line 135, :end-line nil, :hash "1704235782"} {:id "defn/print-progress", :kind "defn", :line 153, :end-line nil, :hash "684719315"} {:id "defn/print-baseline-start", :kind "defn", :line 162, :end-line nil, :hash "1826251280"} {:id "defn/print-baseline-pass", :kind "defn", :line 167, :end-line nil, :hash "558159954"} {:id "defn/print-baseline-fail", :kind "defn", :line 172, :end-line nil, :hash "-348864397"} {:id "defn/print-backup-restored", :kind "defn", :line 176, :end-line nil, :hash "677037454"} {:id "defn/print-manifest-updated", :kind "defn", :line 180, :end-line nil, :hash "-954257762"}]}
;; clj-mutate-manifest-end
