;; mutation-tested: 2026-02-27
(ns clj-mutate.runner
  (:require [clj-mutate.project :as project])
  (:import [java.util.concurrent TimeUnit]))

(defn run-specs
  "Run all specs. Returns :killed, :survived, or :timeout.
   Uses bb or clj depending on project type.
   Optional timeout-ms: kill process after this many milliseconds.
   Optional dir: run specs in the given directory.
   A timeout indicates an infinite loop — treated as :killed by caller."
  ([] (run-specs nil nil))
  ([timeout-ms] (run-specs timeout-ms nil))
  ([timeout-ms dir]
   (let [cmd (project/spec-command (or dir (System/getProperty "user.dir")))
         pb (doto (ProcessBuilder. ^java.util.List cmd)
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
  []
  (let [start (System/currentTimeMillis)
        result (run-specs)
        elapsed (- (System/currentTimeMillis) start)]
    {:result result :elapsed-ms elapsed}))
