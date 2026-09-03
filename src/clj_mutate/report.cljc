(ns clj-mutate.report
  (:require [clojure.string :as str])
  (:import [java.time Instant ZoneId ZonedDateTime]
           [java.time.format DateTimeFormatter]))

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

(defn- human-time
  [epoch-ms]
  (when epoch-ms
    (.format (ZonedDateTime/ofInstant (Instant/ofEpochMilli epoch-ms)
                                      (ZoneId/systemDefault))
             (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss z"))))

(defn- print-coverage-command-output
  [{:keys [exit out err ok?] :as result}]
  (when result
    (when (and (some? exit) (not ok?))
      (println (format "Coverage command exited %d." exit)))
    (when-not (str/blank? out)
      (println "Coverage command stdout:")
      (print out)
      (when-not (str/ends-with? out "\n")
        (println)))
    (when-not (str/blank? err)
      (println "Coverage command stderr:")
      (print err)
      (when-not (str/ends-with? err "\n")
        (println)))))

(defn- print-coverage-status
  [{:keys [status lcov-path last-modified coverage-command-result
           coverage-exit-nonzero?]}]
  (case status
    :fresh
    (println (format "Using fresh LCOV generated %s for the requested mutation test profile."
                     (human-time last-modified)))

    :fresh-reused
    (println (format "Reusing fresh LCOV from %s (generated %s)."
                     lcov-path (human-time last-modified)))

    :stale-reused
    (println (format "Reusing stale LCOV generated %s; source or test files have changed since it was generated."
                     (human-time last-modified)))

    :regenerated
    (do
      (println (format "Generated LCOV for the requested mutation test profile at %s."
                       (human-time last-modified)))
      (when coverage-exit-nonzero?
        (println "Coverage command exited non-zero; using the LCOV it wrote.")
        (print-coverage-command-output coverage-command-result)))

    :refresh-failed
    (do
      (println "Coverage generation failed; existing LCOV was not trusted.")
      (print-coverage-command-output coverage-command-result))

    :missing
    (do
      (println "Coverage data is missing and no usable LCOV file was generated.")
      (print-coverage-command-output coverage-command-result))

    :coverage-disabled
    (println "No coverage command is configured; running mutations without LCOV filtering.")

    nil))

(defn- print-site-counts
  [all-sites covered-sites uncovered changed-mutation-sites coverage-status]
  (println (format "Total mutation sites: %d" (count all-sites)))
  (if (= :coverage-disabled (:status coverage-status))
    (println (format "Coverage filtering disabled; %d located sites will be tested."
                     (count covered-sites)))
    (do
      (println (format "Covered mutation sites: %d" (count covered-sites)))
      (println (format "Uncovered mutation sites: %d" (count uncovered)))))
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

(defn- print-mutation-filter
  [mutation sites]
  (when mutation
    (println (format "Filtering to mutation %s → %d mutation to test."
                     mutation (count sites)))))

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
  ([source-path prev-date header-info lines since-last-run prior-manifest module-unchanged? sites warning-threshold]
   (print-run-header source-path prev-date header-info lines nil since-last-run
                     prior-manifest module-unchanged? sites warning-threshold))
  ([source-path prev-date header-info lines mutation since-last-run prior-manifest module-unchanged? sites warning-threshold]
   (let [{:keys [all-sites covered-sites uncovered changed-mutation-sites
                 manifest-exists? module-hash-changed? coverage-status]
          :as info} header-info]
     (println (format "=== Mutation Testing: %s ===" source-path))
     (print-previous-mutation-test prev-date)
     (print-coverage-status coverage-status)
     (print-site-counts all-sites covered-sites uncovered changed-mutation-sites coverage-status)
     (print-manifest-status manifest-exists? module-hash-changed?)
     (print-surface-area (:surface-area-counts info))
     (print-mutation-warning warning-threshold (count all-sites))
     (print-line-filter lines sites)
     (print-mutation-filter mutation sites)
     (print-since-last-run-filter since-last-run prior-manifest module-unchanged? sites)
     (println))))

(defn- site-id
  [site]
  (or (:display-id site)
      (when (some? (:run-index site)) (format "M%03d" (inc (:run-index site))))
      (when (some? (:index site)) (format "M%03d" (inc (:index site))))
      "M???"))

(defn- site-location
  [site]
  (format "%d:%d" (or (:line site) 0) (or (:column site) 0)))

(defn print-uncovered
  [uncovered]
  (when (seq uncovered)
    (println (format "\n=== Coverage Gaps (%d mutations on uncovered lines) ==="
                     (count uncovered)))
    (doseq [site uncovered]
      (println (format "  %s  %-18s %s  %s"
                       (site-id site) (or (:form-id site) "")
                       (site-location site) (:description site))))))

(defn- print-summary
  [killed total pct survivors uncovered-count]
  (println "\n=== Summary ===")
  (if (zero? total)
    (println "No covered mutations to test.")
    (println (format "%d/%d mutants killed (%.1f%%)" killed total pct)))
  (when (pos? uncovered-count)
    (println (format "%d uncovered mutations remain" uncovered-count)))
  (when (seq survivors)
    (println "Survivors:")
    (doseq [r (sort-by (juxt #(get-in % [:site :line])
                             #(get-in % [:site :column])) survivors)]
      (let [site (:site r)]
        (println (format "  %s  %-18s %s  %s"
                         (site-id site) (or (:form-id site) "")
                         (site-location site) (:description site)))))))

(defn summarize-results
  [results lines since-last-run uncovered]
  (let [killed (count (filter #(= :killed (:result %)) results))
        total (count results)
        pct (if (zero? total) 0.0 (* 100.0 (/ killed total)))
        survivors (vec (filter #(= :survived (:result %)) results))
        uncovered-count (count uncovered)]
    (print-summary killed total pct survivors uncovered-count)
    {:mutations total
     :killed killed
     :survivors (count survivors)
     :uncovered uncovered-count}))

(defn- result-label
  [r]
  (cond
    (:timeout? r) "TIMEOUT"
    (= :killed (:result r)) "KILLED"
    :else "SURVIVED"))

(defn- format-line
  [i total r]
  (let [site (:site r)]
    (format "[%3d/%d] %s  %-8s  %-18s %s  %s%n"
            (inc i) total (site-id site) (result-label r)
            (or (:form-id site) "") (site-location site) (:description site))))

(defn- format-survivor
  [r]
  (let [site (:site r)]
    (format "  %s  %-18s %s  %s%n"
            (site-id site) (or (:form-id site) "")
            (site-location site) (:description site))))

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
      (if (zero? total)
        "No covered mutations to test.\n"
        (format "%d/%d mutants killed (%.1f%%)%n" killed total pct))
      (when (pos? uncovered-count)
        (format "%d uncovered mutations remain%n" uncovered-count))
      (when (seq survivors)
        (str "Survivors:\n" (apply str (map format-survivor survivors)))))))

(defn print-progress
  [i total result site]
  (println (format "[%3d/%d] %s  %-8s  %-18s %s  %s"
                   (inc i) total (site-id site) (result-label result)
                   (or (:form-id site) "") (site-location site)
                   (:description site)))
  (flush))

(defn print-no-changes
  [source-path previous-date]
  (println (format "=== Mutation Testing: %s ===" source-path))
  (println (format "No changes since the successful mutation run at %s."
                   previous-date))
  (println "No mutations to test."))

(defn print-configuration-error
  [message]
  (println (str "Error: " message)))

(defn print-baseline-start [] (print "Baseline: ") (flush))

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
  (println (str "Updated unverified manifest: " source-path)))

(defn print-verified-manifest-skipped
  []
  (println "Not writing a verified manifest because the test command selects namespaces with -n/--namespace."))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-09-02T15:18:37.279832-05:00", :module-hash "144987029", :forms []}
;; clj-mutate-manifest-end
