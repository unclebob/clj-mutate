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
