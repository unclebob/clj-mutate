(ns clj-mutate.coverage
  (:require [clj-mutate.lcov :as lcov]
            [clj-mutate.project :as project]
            [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.io File]))

(defn lcov-path
  "Return the path to the LCOV info file."
  []
  "target/coverage/lcov.info")

(defn provenance-path
  []
  "target/coverage/clj-mutate.edn")

(defn- command->argv
  [command]
  (when-not (str/blank? command)
    (str/split (str/trim command) #"\s+")))

(defn run-coverage!
  "Run coverage-command. Returns true on success."
  ([] (run-coverage! (project/default-coverage-command)))
  ([coverage-command]
   (when-let [argv (command->argv coverage-command)]
     (zero? (:exit (apply shell/sh argv))))))

(defn- newest-file-mtime
  "Return the newest mtime among regular files under dir, or 0."
  [^File dir]
  (if (.exists dir)
    (reduce max 0 (map #(.lastModified ^File %)
                       (filter #(.isFile ^File %) (file-seq dir))))
    0))

(defn- newest-input-mtime
  "Return newest mtime across source and conventional source/spec roots."
  [source-path]
  (let [source-file (File. source-path)
        source-mtime (if (.exists source-file) (.lastModified source-file) 0)
        roots (concat ["src"] (project/test-directories))]
    (apply max source-mtime (map #(newest-file-mtime (File. %)) roots))))

(defn- stale-reason
  "Return nil when fresh, otherwise one of :missing or :stale."
  [^File lcov-file source-path]
  (cond
    (not (.exists lcov-file)) :missing
    (< (.lastModified lcov-file) (newest-input-mtime source-path)) :stale
    :else nil))

(defn- read-provenance
  []
  (let [file (File. (provenance-path))]
    (when (.exists file)
      (try
        (edn/read-string (slurp file))
        (catch Exception _ nil)))))

(defn- expected-provenance
  [coverage-command test-command]
  {:coverage-command coverage-command
   :test-command test-command
   :test-profile-fingerprint (project/test-profile-fingerprint test-command)})

(defn- write-provenance!
  [provenance]
  (let [file (File. (provenance-path))]
    (.mkdirs (.getParentFile file))
    (spit file (pr-str provenance))))

(defn coverage-status
  ([source-path] (coverage-status source-path {}))
  ([source-path {:keys [coverage-command test-command]}]
  (let [lcov-file (File. (lcov-path))
        reason (stale-reason lcov-file source-path)
        expected (when test-command
                   (expected-provenance coverage-command test-command))
        recorded (read-provenance)]
    {:lcov-path (lcov-path)
     :exists? (.exists lcov-file)
     :last-modified (when (.exists lcov-file) (.lastModified lcov-file))
     :source-newer? (when (.exists lcov-file)
                      (> (newest-input-mtime source-path) (.lastModified lcov-file)))
     :stale-reason reason
     :profile-match? (when expected (= expected recorded))
     :recorded-provenance recorded
     :expected-provenance expected})))

(defn- covered-lines-from-lcov
  [lcov-file source-path]
  (when (.exists lcov-file)
    (lcov/covered-lines (lcov/parse-lcov (slurp lcov-file)) source-path)))

(defn- missing-reuse-error
  [source-path]
  (ex-info "LCOV reuse requested, but target/coverage/lcov.info is missing."
           {:source-path source-path
            :lcov-path (lcov-path)
            :reason :missing-lcov-for-reuse}))

(defn- profile-reuse-error
  [source-path status]
  (ex-info "LCOV reuse requested, but it was generated for a different or unknown test profile."
           {:source-path source-path
            :lcov-path (lcov-path)
            :reason :coverage-profile-mismatch
            :recorded-provenance (:recorded-provenance status)
            :expected-provenance (:expected-provenance status)}))

(defn load-coverage
  "Load coverage and return structured lines/status data without printing."
  ([source-path]
   (load-coverage source-path {}))
  ([source-path options]
   (let [reuse-lcov (:reuse-lcov options false)
         coverage-disabled? (false? (:coverage-command options))
         coverage-command (if (false? (:coverage-command options))
                            nil
                            (or (:coverage-command options)
                                (project/default-coverage-command)))
         test-command (or (:test-command options)
                          (project/default-test-command))
         lcov-file (File. (lcov-path))
         expected (expected-provenance coverage-command test-command)
         initial (coverage-status source-path {:coverage-command coverage-command
                                               :test-command test-command})
         reason (:stale-reason initial)
         profile-match? (:profile-match? initial)]
     (cond
       coverage-disabled?
       (assoc initial :lines nil :status :coverage-disabled)

       (and reuse-lcov (= :missing reason))
       (throw (missing-reuse-error source-path))

       (and reuse-lcov (not profile-match?))
       (throw (profile-reuse-error source-path initial))

       reuse-lcov
       (assoc initial
              :lines (covered-lines-from-lcov lcov-file source-path)
              :status (if (= :stale reason) :stale-reused :fresh-reused))

       (and (nil? reason) profile-match?)
       (assoc initial
              :lines (covered-lines-from-lcov lcov-file source-path)
              :status :fresh)

       coverage-command
       (if (run-coverage! coverage-command)
         (if (nil? (stale-reason lcov-file source-path))
           (do
             (write-provenance! expected)
             (let [refreshed (coverage-status source-path {:coverage-command coverage-command
                                                           :test-command test-command})]
               (assoc refreshed
                      :lines (covered-lines-from-lcov lcov-file source-path)
                      :status :regenerated)))
           (assoc initial
                  :lines nil
                  :status (if (.exists lcov-file) :refresh-failed :missing)))
         (assoc initial
                :lines (covered-lines-from-lcov lcov-file source-path)
                :status (if (.exists lcov-file) :refresh-failed :missing)))

       :else
       (assoc initial
              :lines nil
              :status :coverage-disabled)))))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-09-02T15:17:24.937154-05:00", :module-hash "-910111229", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line nil, :hash "1909002017"} {:id "form/1/declare", :kind "declare", :line 7, :end-line nil, :hash "618546487"} {:id "defn/lcov-path", :kind "defn", :line 9, :end-line nil, :hash "1578938533"} {:id "defn/run-coverage!", :kind "defn", :line 14, :end-line nil, :hash "359690965"} {:id "defn-/newest-file-mtime", :kind "defn-", :line 20, :end-line nil, :hash "-71730915"} {:id "defn-/newest-input-mtime", :kind "defn-", :line 28, :end-line nil, :hash "1797432555"} {:id "defn-/stale-reason", :kind "defn-", :line 37, :end-line nil, :hash "869474894"} {:id "defn/coverage-status", :kind "defn", :line 45, :end-line nil, :hash "1393845949"} {:id "defn-/covered-lines-from-lcov", :kind "defn-", :line 56, :end-line nil, :hash "75634368"} {:id "defn-/reuse-or-refresh-coverage!", :kind "defn-", :line 61, :end-line nil, :hash "-1900961287"} {:id "defn/load-coverage", :kind "defn", :line 78, :end-line nil, :hash "-689699037"}]}
;; clj-mutate-manifest-end
