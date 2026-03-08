;; mutation-tested: 2026-02-27
(ns clj-mutate.runner
  (:require [clojure.string :as str])
  (:import [java.util.concurrent TimeUnit]))

(def ^:private default-test-command "clj -M:spec")

(defn- command->argv
  [command]
  (let [argv (->> (str/split (or command "") #"\s+")
                  (remove str/blank?)
                  vec)]
    (if (seq argv)
      argv
      (str/split default-test-command #"\s+"))))

(defn run-specs
  "Run all specs. Returns :killed, :survived, or :timeout.
   Optional timeout-ms: kill process after this many milliseconds.
   Optional dir: run specs in the given directory.
   Optional test-command: shell-like command string (split on whitespace).
   A timeout indicates an infinite loop — treated as :killed by caller."
  ([] (run-specs nil nil default-test-command))
  ([timeout-ms] (run-specs timeout-ms nil default-test-command))
  ([timeout-ms dir] (run-specs timeout-ms dir default-test-command))
  ([timeout-ms dir test-command]
   (let [pb (doto (ProcessBuilder. ^java.util.List (command->argv test-command))
              (.redirectErrorStream true))
         _ (when dir (.directory pb (java.io.File. dir)))
         process (.start pb)
         _ (doto (Thread. (fn [] (try (let [is (.getInputStream process)]
                                        (while (not= -1 (.read is))))
                                      (catch Exception _))))
             (.setDaemon true)
             (.start))
         finished? (if timeout-ms
                     (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)
                     (do (.waitFor process) true))]
     (if finished?
       (if (zero? (.exitValue process))
         :survived
         :killed)
       (do (.destroyForcibly process)
           :timeout)))))

(defn run-specs-timed
  "Run all specs without timeout. Returns {:result :killed/:survived :elapsed-ms N}."
  ([]
   (run-specs-timed default-test-command))
  ([test-command]
  (let [start (System/currentTimeMillis)
        result (run-specs nil nil test-command)
        elapsed (- (System/currentTimeMillis) start)]
    {:result result :elapsed-ms elapsed})))
