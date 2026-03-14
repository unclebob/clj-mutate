(ns clj-mutate.coverage
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell])
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

(defn load-coverage
  "Orchestrator: run coverage if lcov.info missing/stale, parse, return covered lines."
  ([source-path]
   (load-coverage source-path {}))
  ([source-path {:keys [reuse-lcov] :or {reuse-lcov false}}]
   (let [lcov-file (File. (lcov-path))]
     (when-let [reason (stale-reason lcov-file source-path)]
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
     (when (.exists lcov-file)
       (covered-lines (parse-lcov (slurp lcov-file)) source-path)))))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-03-14T08:11:44.600436-05:00", :module-hash "-384753843", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line 4, :hash "-1514868448"} {:id "form/1/declare", :kind "declare", :line 6, :end-line 6, :hash "618546487"} {:id "defn-/parse-data-line", :kind "defn-", :line 8, :end-line 11, :hash "1753012357"} {:id "defn-/apply-lcov-line", :kind "defn-", :line 13, :end-line 30, :hash "1226843982"} {:id "defn/parse-lcov", :kind "defn", :line 32, :end-line 38, :hash "-1778570802"} {:id "defn/covered-lines", :kind "defn", :line 40, :end-line 46, :hash "-1062448005"} {:id "defn/lcov-path", :kind "defn", :line 48, :end-line 51, :hash "1578938533"} {:id "defn/run-coverage!", :kind "defn", :line 53, :end-line 57, :hash "359690965"} {:id "defn-/newest-file-mtime", :kind "defn-", :line 59, :end-line 65, :hash "1446395847"} {:id "defn-/newest-input-mtime", :kind "defn-", :line 67, :end-line 74, :hash "1797432555"} {:id "defn-/stale-reason", :kind "defn-", :line 76, :end-line 82, :hash "869474894"} {:id "defn/coverage-status", :kind "defn", :line 84, :end-line 93, :hash "1393845949"} {:id "defn/load-coverage", :kind "defn", :line 95, :end-line 115, :hash "1539619016"}]}
;; clj-mutate-manifest-end
