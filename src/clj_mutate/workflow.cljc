(ns clj-mutate.workflow
  (:require [clojure.string :as str]
            [clj-mutate.coverage :as coverage]
            [clj-mutate.execution :as execution]
            [clj-mutate.manifest :as manifest]
            [clj-mutate.runner :as runner]
            [clj-mutate.source :as source]))

(defn filter-by-lines
  [sites lines]
  (if lines
    (vec (filter #(contains? lines (:line %)) sites))
    sites))

(defn filter-by-form-indices
  [sites form-indices]
  (if form-indices
    (vec (filter #(contains? form-indices (:form-index %)) sites))
    sites))

(defn mutation-run-context
  [source-path since-last-run]
  (let [original-content (slurp source-path)
        prior-manifest (manifest/extract-embedded-manifest original-content)
        analysis-content (manifest/strip-mutation-metadata original-content)
        forms (source/read-source-forms analysis-content)
        current-module-hash (manifest/module-hash forms)
        module-unchanged? (and since-last-run
                               prior-manifest
                               (= current-module-hash (:module-hash prior-manifest)))
        changed-forms (when (and since-last-run prior-manifest (not module-unchanged?))
                        (manifest/changed-form-indices forms prior-manifest))
        all-sites (source/discover-all-mutations forms)
        covered-lines (coverage/load-coverage source-path)
        [covered-sites uncovered] (source/partition-by-coverage all-sites covered-lines)]
    {:original-content original-content
     :prev-date (manifest/extract-mutation-date original-content)
     :prior-manifest prior-manifest
     :analysis-content analysis-content
     :forms forms
     :module-unchanged? module-unchanged?
     :all-sites all-sites
     :covered-sites covered-sites
     :uncovered uncovered
     :sites nil
     :manifest-content (manifest/embed-mutation-manifest analysis-content
                                                        (manifest/build-embedded-manifest forms (manifest/now-str)))
     :changed-forms changed-forms}))

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
    (when prev-date
      (println (format "Previous mutation test: %s" prev-date)))
    (println (format "Found %d mutation sites." (count all-sites)))
    (println (format "Changed mutation sites: %d" changed-sites))
    (print-mutation-warning mutation-warning (count all-sites))))

(defn print-run-header
  [source-path prev-date all-sites covered-sites uncovered lines since-last-run prior-manifest module-unchanged? sites warning-threshold]
  (println (format "=== Mutation Testing: %s ===" source-path))
  (when prev-date
    (println (format "Previous mutation test: %s" prev-date)))
  (println (format "Found %d mutation sites (%d covered, %d uncovered)."
                   (count all-sites) (count covered-sites) (count uncovered)))
  (print-mutation-warning warning-threshold (count all-sites))
  (when lines
    (println (format "Filtering to lines: %s → %d mutations to test."
                     (str/join "," (sort lines)) (count sites))))
  (when since-last-run
    (if prior-manifest
      (if module-unchanged?
        (println "Module hash unchanged; no mutations to test.")
        (println (format "Filtering to changed top-level forms → %d mutations to test."
                         (count sites))))
      (println "No prior embedded manifest found; running all covered mutations.")))
  (println))

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
;; {:version 1, :tested-at "2026-03-12T09:07:06.527108-05:00", :module-hash "1934561817", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line 7, :hash "673503783"} {:id "defn/filter-by-lines", :kind "defn", :line 9, :end-line 13, :hash "642993194"} {:id "defn/filter-by-form-indices", :kind "defn", :line 15, :end-line 19, :hash "554023203"} {:id "defn/mutation-run-context", :kind "defn", :line 21, :end-line 48, :hash "-199343365"} {:id "defn/default-since-last-run?", :kind "defn", :line 50, :end-line 54, :hash "-71751391"} {:id "defn/select-mutation-sites", :kind "defn", :line 56, :end-line 62, :hash "-1457564386"} {:id "defn/print-mutation-warning", :kind "defn", :line 64, :end-line 67, :hash "-8091185"} {:id "defn-/count-changed-sites", :kind "defn-", :line 69, :end-line 74, :hash "2117283179"} {:id "defn/scan-mutation-sites", :kind "defn", :line 76, :end-line 90, :hash "-777824472"} {:id "defn/print-run-header", :kind "defn", :line 92, :end-line 110, :hash "-2011127734"} {:id "defn/print-uncovered", :kind "defn", :line 112, :end-line 118, :hash "-1174101582"} {:id "defn-/print-summary", :kind "defn-", :line 120, :end-line 132, :hash "-647776276"} {:id "defn/summarize-results", :kind "defn", :line 134, :end-line 140, :hash "617629029"} {:id "defn/run-mutation-suite", :kind "defn", :line 142, :end-line 146, :hash "307720017"} {:id "defn/with-baseline", :kind "defn", :line 148, :end-line 159, :hash "2111533211"}]}
;; clj-mutate-manifest-end
