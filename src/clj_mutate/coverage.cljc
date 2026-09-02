(ns clj-mutate.coverage
  (:require [clj-mutate.lcov :as lcov]
            [clj-mutate.project :as project]
            [clojure.java.shell :as shell])
  (:import [java.io File]))

(declare stale-reason)

(defn lcov-path
  "Return the path to the LCOV info file."
  []
  "target/coverage/lcov.info")

(defn run-coverage!
  "Shell out to clj -M:cov --lcov. Returns true on success."
  []
  (let [result (shell/sh "clj" "-M:cov" "--lcov")]
    (zero? (:exit result))))

(defn- newest-file-mtime
  "Return the newest mtime among regular files under dir, or 0."
  [^File dir]
  (if (.exists dir)
    (reduce max 0 (map #(.lastModified ^File %)
                       (filter #(.isFile ^File %) (file-seq dir))))
    0))

(defn- newest-input-mtime
  "Return newest mtime across source-path, src/, and spec/."
  [source-path]
  (let [source-file (File. source-path)
        source-mtime (if (.exists source-file) (.lastModified source-file) 0)
        src-mtime (newest-file-mtime (File. "src"))
        spec-mtime (newest-file-mtime (File. "spec"))]
    (max source-mtime src-mtime spec-mtime)))

(defn- stale-reason
  "Return nil when fresh, otherwise one of :missing or :stale."
  [^File lcov-file source-path]
  (cond
    (not (.exists lcov-file)) :missing
    (< (.lastModified lcov-file) (newest-input-mtime source-path)) :stale
    :else nil))

(defn coverage-status
  [source-path]
  (let [lcov-file (File. (lcov-path))
        reason (stale-reason lcov-file source-path)]
    {:lcov-path (lcov-path)
     :exists? (.exists lcov-file)
     :last-modified (when (.exists lcov-file) (.lastModified lcov-file))
     :source-newer? (when (.exists lcov-file)
                      (> (newest-input-mtime source-path) (.lastModified lcov-file)))
     :stale-reason reason}))

(defn- covered-lines-from-lcov
  [lcov-file source-path]
  (when (.exists lcov-file)
    (lcov/covered-lines (lcov/parse-lcov (slurp lcov-file)) source-path)))

(defn- reuse-or-refresh-coverage!
  [reason source-path reuse-lcov]
  (if reuse-lcov
    (case reason
      :missing (throw (ex-info "LCOV reuse requested, but target/coverage/lcov.info is missing."
                               {:source-path source-path
                                :lcov-path (lcov-path)
                                :reason :missing-lcov-for-reuse}))
      :stale (println "Reusing existing LCOV data from target/coverage/lcov.info even though it is stale."))
    (do
      (println
        (case reason
          :missing "Coverage file missing; regenerating LCOV with clj -M:cov --lcov."
          :stale "Coverage file is stale; regenerating LCOV with clj -M:cov --lcov."))
      (when-not (run-coverage!)
        (println "Coverage refresh failed; continuing with existing coverage if available.")))))

(defn load-coverage
  "Orchestrator: run coverage if lcov.info missing/stale, parse, return covered lines."
  ([source-path]
   (load-coverage source-path {}))
  ([source-path options]
   (let [reuse-lcov (:reuse-lcov options false)
         lcov-file (File. (lcov-path))]
     (if (project/bb-project?)
       (covered-lines-from-lcov lcov-file source-path)
       (do
         (when-let [reason (stale-reason lcov-file source-path)]
           (reuse-or-refresh-coverage! reason source-path reuse-lcov))
         (covered-lines-from-lcov lcov-file source-path))))))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-09-02T15:17:24.937154-05:00", :module-hash "-910111229", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line nil, :hash "1909002017"} {:id "form/1/declare", :kind "declare", :line 7, :end-line nil, :hash "618546487"} {:id "defn/lcov-path", :kind "defn", :line 9, :end-line nil, :hash "1578938533"} {:id "defn/run-coverage!", :kind "defn", :line 14, :end-line nil, :hash "359690965"} {:id "defn-/newest-file-mtime", :kind "defn-", :line 20, :end-line nil, :hash "-71730915"} {:id "defn-/newest-input-mtime", :kind "defn-", :line 28, :end-line nil, :hash "1797432555"} {:id "defn-/stale-reason", :kind "defn-", :line 37, :end-line nil, :hash "869474894"} {:id "defn/coverage-status", :kind "defn", :line 45, :end-line nil, :hash "1393845949"} {:id "defn-/covered-lines-from-lcov", :kind "defn-", :line 56, :end-line nil, :hash "75634368"} {:id "defn-/reuse-or-refresh-coverage!", :kind "defn-", :line 61, :end-line nil, :hash "-1900961287"} {:id "defn/load-coverage", :kind "defn", :line 78, :end-line nil, :hash "-689699037"}]}
;; clj-mutate-manifest-end
