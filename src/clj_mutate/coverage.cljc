(ns clj-mutate.coverage
  (:require [clj-mutate.lcov :as lcov]
            [clj-mutate.project :as project]
            [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.file CopyOption Files StandardCopyOption]
           [java.util UUID]))

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
  ([coverage-command test-command]
   (expected-provenance coverage-command test-command nil))
  ([coverage-command test-command test-roots]
   {:coverage-command coverage-command
    :test-command test-command
    :test-roots (project/test-profile-roots (System/getProperty "user.dir")
                                            test-command test-roots)
    :test-profile-fingerprint
    (project/test-profile-fingerprint (System/getProperty "user.dir")
                                      test-command test-roots)
    :coverage-profile-fingerprint
    (project/test-profile-fingerprint (System/getProperty "user.dir")
                                      coverage-command test-roots)}))

(defn- write-provenance!
  [provenance]
  (let [file (File. (provenance-path))
        parent (.getParentFile file)
        temp (File. parent (str ".clj-mutate-provenance-" (UUID/randomUUID) ".tmp"))]
    (.mkdirs (.getParentFile file))
    (try
      (spit temp (pr-str provenance))
      (Files/move (.toPath temp) (.toPath file)
                  (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
      (finally
        (Files/deleteIfExists (.toPath temp))))))

(defn coverage-status
  ([source-path] (coverage-status source-path {}))
  ([source-path {:keys [coverage-command test-command test-roots]}]
  (let [lcov-file (File. (lcov-path))
        reason (stale-reason lcov-file source-path)
        expected (when test-command
                   (expected-provenance coverage-command test-command test-roots))
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
    (let [parsed (lcov/parse-lcov (slurp lcov-file))]
      (when-not (seq parsed)
        (throw (ex-info "LCOV contains no source records."
                        {:reason :invalid-lcov
                         :lcov-path (.getPath lcov-file)})))
      (or (lcov/covered-lines parsed source-path) #{}))))

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

(defn- incompatible-profile-error
  [test-command coverage-command test-roots]
  (ex-info "Coverage and mutation commands do not identify the same test population."
           {:reason :coverage-test-profile-mismatch
            :test-command test-command
            :coverage-command coverage-command
            :test-roots test-roots}))

(defn- move-aside!
  [^File file prefix]
  (when (.exists file)
    (let [backup (File. (.getParentFile file)
                        (str "." prefix "-" (UUID/randomUUID) ".previous"))]
      (Files/move (.toPath file) (.toPath backup)
                  (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
      backup)))

(defn- rollback-file!
  [^File file ^File backup]
  (Files/deleteIfExists (.toPath file))
  (when (and backup (.exists backup))
    (Files/move (.toPath backup) (.toPath file)
                (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))

(defn- remove-backup!
  [^File backup]
  (when backup
    (try
      (Files/deleteIfExists (.toPath backup))
      (catch Exception _ false))))

(defn- regeneration-failure
  [initial backup]
  (assoc initial
         :lines nil
         :status (if backup :refresh-failed :missing)))

(defn- commit-provenance!
  [source-path options expected]
  (let [provenance-file (File. (provenance-path))
        backup (move-aside! provenance-file "clj-mutate-provenance")]
    (try
      (write-provenance! expected)
      (let [refreshed (coverage-status source-path options)]
        (remove-backup! backup)
        refreshed)
      (catch Exception ex
        (rollback-file! provenance-file backup)
        (throw ex))
      (finally
        (remove-backup! backup)))))

(defn- regenerate-coverage
  [source-path lcov-file coverage-command expected initial options]
  (let [backup (move-aside! lcov-file "lcov")]
    (try
      (if (and (run-coverage! coverage-command)
               (.exists lcov-file)
               (nil? (stale-reason lcov-file source-path)))
        (let [lines (covered-lines-from-lcov lcov-file source-path)]
          (let [refreshed (commit-provenance! source-path options expected)]
            (remove-backup! backup)
            (assoc refreshed :lines lines :status :regenerated)))
        (do
          (rollback-file! lcov-file backup)
          (regeneration-failure initial backup)))
      (catch Exception _
        (rollback-file! lcov-file backup)
        (regeneration-failure initial backup))
      (finally
        (remove-backup! backup)))))

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
         test-roots (:test-roots options)
         lcov-file (File. (lcov-path))
         expected (expected-provenance coverage-command test-command test-roots)
         initial (coverage-status source-path {:coverage-command coverage-command
                                               :test-command test-command
                                               :test-roots test-roots})
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

       (nil? coverage-command)
       (assoc initial :lines nil :status :coverage-disabled)

       (not (project/commands-share-test-profile?
              (System/getProperty "user.dir") test-command coverage-command test-roots))
       (throw (incompatible-profile-error test-command coverage-command test-roots))

       (and (nil? reason) profile-match?)
       (assoc initial
              :lines (covered-lines-from-lcov lcov-file source-path)
              :status :fresh)

       coverage-command
       (regenerate-coverage source-path lcov-file coverage-command expected initial
                            {:coverage-command coverage-command
                             :test-command test-command
                             :test-roots test-roots})

       :else
       (assoc initial
              :lines nil
              :status :coverage-disabled)))))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-09-02T15:17:24.937154-05:00", :module-hash "-910111229", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line nil, :hash "1909002017"} {:id "form/1/declare", :kind "declare", :line 7, :end-line nil, :hash "618546487"} {:id "defn/lcov-path", :kind "defn", :line 9, :end-line nil, :hash "1578938533"} {:id "defn/run-coverage!", :kind "defn", :line 14, :end-line nil, :hash "359690965"} {:id "defn-/newest-file-mtime", :kind "defn-", :line 20, :end-line nil, :hash "-71730915"} {:id "defn-/newest-input-mtime", :kind "defn-", :line 28, :end-line nil, :hash "1797432555"} {:id "defn-/stale-reason", :kind "defn-", :line 37, :end-line nil, :hash "869474894"} {:id "defn/coverage-status", :kind "defn", :line 45, :end-line nil, :hash "1393845949"} {:id "defn-/covered-lines-from-lcov", :kind "defn-", :line 56, :end-line nil, :hash "75634368"} {:id "defn-/reuse-or-refresh-coverage!", :kind "defn-", :line 61, :end-line nil, :hash "-1900961287"} {:id "defn/load-coverage", :kind "defn", :line 78, :end-line nil, :hash "-689699037"}]}
;; clj-mutate-manifest-end
