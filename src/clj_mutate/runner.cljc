(ns clj-mutate.runner
  (:require [clojure.string :as str]
            [clj-mutate.project :as project])
  (:import [java.util.concurrent TimeUnit]))

(defn- default-test-command
  []
  (project/default-test-command))

(defn- command->argv
  [command]
  (let [argv (->> (str/split (or command "") #"\s+")
                  (remove str/blank?)
                  vec)]
    (if (seq argv)
      argv
      (str/split (default-test-command) #"\s+"))))

(defn- create-process-builder
  [test-command]
  (doto (ProcessBuilder. ^java.util.List (command->argv test-command))
    (.redirectErrorStream true)))

(defn- create-thread
  [f]
  (Thread. f))

(defn- mark-thread-daemon!
  [thread]
  (.setDaemon thread true)
  thread)

(defn- start-thread!
  [thread]
  (.start thread)
  thread)

(defn- start-process
  [dir test-command]
  (let [pb (create-process-builder test-command)]
    (when dir
      (.directory pb (java.io.File. dir)))
    (.start pb)))

(defn- start-output-drainer!
  [process]
  (-> (create-thread (fn [] (try (let [is (.getInputStream process)]
                                   (while (not= -1 (.read is))))
                                 (catch Exception _))))
      (mark-thread-daemon!)
      (start-thread!)))

(defn- wait-for-process
  [process timeout-ms]
  (if timeout-ms
    (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)
    (do (.waitFor process) true)))

(defn- current-time-ms
  []
  (System/currentTimeMillis))

(defn run-specs
  "Run all specs. Returns :killed, :survived, or :timeout.
   Optional timeout-ms: kill process after this many milliseconds.
   Optional dir: run specs in the given directory.
   Optional test-command: shell-like command string (split on whitespace).
   A timeout indicates an infinite loop — treated as :killed by caller."
  ([] (run-specs nil nil (default-test-command)))
  ([timeout-ms] (run-specs timeout-ms nil (default-test-command)))
  ([timeout-ms dir] (run-specs timeout-ms dir (default-test-command)))
  ([timeout-ms dir test-command]
   (let [process (start-process dir test-command)
         _ (start-output-drainer! process)
         finished? (wait-for-process process timeout-ms)]
     (if finished?
       (if (zero? (.exitValue process))
         :survived
         :killed)
       (do (.destroyForcibly process)
           :timeout)))))

(defn run-specs-timed
  "Run all specs without timeout. Returns {:result :killed/:survived :elapsed-ms N}."
  ([]
   (run-specs-timed (default-test-command)))
  ([test-command]
  (let [start (current-time-ms)
        result (run-specs nil nil test-command)
        elapsed (- (current-time-ms) start)]
    {:result result :elapsed-ms elapsed})))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-09-02T15:17:50.850607-05:00", :module-hash "208137461", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line nil, :hash "-1092773539"} {:id "defn-/default-test-command", :kind "defn-", :line 6, :end-line nil, :hash "273742881"} {:id "defn-/command->argv", :kind "defn-", :line 10, :end-line nil, :hash "767767993"} {:id "defn-/create-process-builder", :kind "defn-", :line 19, :end-line nil, :hash "269904755"} {:id "defn-/create-thread", :kind "defn-", :line 24, :end-line nil, :hash "1239251161"} {:id "defn-/mark-thread-daemon!", :kind "defn-", :line 28, :end-line nil, :hash "-1927689351"} {:id "defn-/start-thread!", :kind "defn-", :line 33, :end-line nil, :hash "1533596915"} {:id "defn-/start-process", :kind "defn-", :line 38, :end-line nil, :hash "1984898807"} {:id "defn-/start-output-drainer!", :kind "defn-", :line 45, :end-line nil, :hash "-820290630"} {:id "defn-/wait-for-process", :kind "defn-", :line 53, :end-line nil, :hash "-1307078638"} {:id "defn-/current-time-ms", :kind "defn-", :line 59, :end-line nil, :hash "-1606704060"} {:id "defn/run-specs", :kind "defn", :line 63, :end-line nil, :hash "-1795545388"} {:id "defn/run-specs-timed", :kind "defn", :line 83, :end-line nil, :hash "-148119943"}]}
;; clj-mutate-manifest-end
