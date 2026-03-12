(ns clj-mutate.coverage
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell])
  (:import [java.io File]))

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

(defn load-coverage
  "Orchestrator: run coverage if lcov.info missing/stale, parse, return covered lines."
  [source-path]
  (let [lcov-file (File. (lcov-path))]
    (when-let [reason (stale-reason lcov-file source-path)]
      (println
        (case reason
          :missing "Coverage file missing; regenerating LCOV with clj -M:cov --lcov."
          :stale "Coverage file is stale; regenerating LCOV with clj -M:cov --lcov."))
      (when-not (run-coverage!)
        (println "Coverage refresh failed; continuing with existing coverage if available.")))
    (when (.exists lcov-file)
      (covered-lines (parse-lcov (slurp lcov-file)) source-path))))
