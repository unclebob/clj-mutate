(ns clj-mutate.coverage
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]
            [clj-mutate.project :as project])
  (:import [java.io File]))

(declare stale-reason)

(defn- parse-data-line [line]
  (let [[line-num count] (str/split (subs line 3) #",")]
    {:line-num (parse-long line-num)
     :count (parse-long count)}))

(defn- apply-lcov-line [{:keys [current-file result] :as state} line]
  (cond
    (str/starts-with? line "SF:")
    {:current-file (subs line 3)
     :result (assoc result (subs line 3) #{})}

    (str/starts-with? line "DA:")
    (let [{:keys [line-num count]} (parse-data-line line)]
      (if (and current-file (pos? count))
        {:current-file current-file
         :result (update result current-file conj line-num)}
        state))

    (= "end_of_record" line)
    (assoc state :current-file nil)

    :else
    state))

(defn parse-lcov
  "Parse LCOV text into {\"file-path\" #{covered-line-numbers}}."
  [lcov-content]
  (:result
    (reduce apply-lcov-line
            {:current-file nil :result {}}
            (str/split-lines lcov-content))))

(defn covered-lines
  "Return set of covered lines for source-path from lcov-map.
   Handles both exact and suffix matches."
  [lcov-map source-path]
  (or (get lcov-map source-path)
      (some (fn [[k v]] (when (str/ends-with? k source-path) v))
            lcov-map)))

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
    (covered-lines (parse-lcov (slurp lcov-file)) source-path)))

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
  ([source-path {:keys [reuse-lcov] :or {reuse-lcov false}}]
   (let [lcov-file (File. (lcov-path))]
     (if (project/bb-project?)
       (covered-lines-from-lcov lcov-file source-path)
       (do
         (when-let [reason (stale-reason lcov-file source-path)]
           (reuse-or-refresh-coverage! reason source-path reuse-lcov))
         (covered-lines-from-lcov lcov-file source-path))))))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-09-02T14:42:51.036625-05:00", :module-hash "805429447", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line 5, :hash "-463502720"} {:id "form/1/declare", :kind "declare", :line 7, :end-line 7, :hash "618546487"} {:id "defn-/parse-data-line", :kind "defn-", :line 9, :end-line 12, :hash "1753012357"} {:id "defn-/apply-lcov-line", :kind "defn-", :line 14, :end-line 31, :hash "1226843982"} {:id "defn/parse-lcov", :kind "defn", :line 33, :end-line 39, :hash "-1778570802"} {:id "defn/covered-lines", :kind "defn", :line 41, :end-line 47, :hash "-1062448005"} {:id "defn/lcov-path", :kind "defn", :line 49, :end-line 52, :hash "1578938533"} {:id "defn/run-coverage!", :kind "defn", :line 54, :end-line 58, :hash "359690965"} {:id "defn-/newest-file-mtime", :kind "defn-", :line 60, :end-line 66, :hash "803400633"} {:id "defn-/newest-input-mtime", :kind "defn-", :line 68, :end-line 75, :hash "1797432555"} {:id "defn-/stale-reason", :kind "defn-", :line 77, :end-line 83, :hash "869474894"} {:id "defn/coverage-status", :kind "defn", :line 85, :end-line 94, :hash "1393845949"} {:id "defn-/covered-lines-from-lcov", :kind "defn-", :line 96, :end-line 99, :hash "2031635723"} {:id "defn-/reuse-or-refresh-coverage!", :kind "defn-", :line 101, :end-line 116, :hash "-1900961287"} {:id "defn/load-coverage", :kind "defn", :line 118, :end-line 129, :hash "-866658605"}]}
;; clj-mutate-manifest-end
