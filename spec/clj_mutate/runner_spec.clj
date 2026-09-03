(ns clj-mutate.runner-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.project :as project]
            [clj-mutate.runner :as runner])
  (:import [java.io File]))

(describe "run-specs"
  (it "accepts a timeout-ms and dir parameter"
    (let [temp-dir (doto (File. (str "target/test-runner-" (System/nanoTime)))
                     (.mkdirs))
          dir-path (.getPath temp-dir)]
      (try
        ;; Just verify the 2-arity exists — call with nonsense dir
        ;; that will fail fast (no deps.edn). The point is no
        ;; ArityException.
        (should-not-throw
          (try (runner/run-specs 100 dir-path)
               (catch Exception e
                 (when (instance? clojure.lang.ArityException e)
                   (throw e)))))
        (finally
          (.delete temp-dir)))))

  (it "accepts a timeout-ms, dir, and test-command parameter"
    (let [temp-dir (doto (File. (str "target/test-runner-" (System/nanoTime)))
                     (.mkdirs))
          dir-path (.getPath temp-dir)]
      (try
        (should-not-throw
          (try (runner/run-specs 100 dir-path "clj -M:spec")
               (catch Exception e
                 (when (instance? clojure.lang.ArityException e)
                   (throw e)))))
        (finally
          (.delete temp-dir))))))

(describe "command->argv"
  (it "falls back to the default command when input is blank"
    (should= (project/spec-command) (#'runner/command->argv "   ")))

  (it "splits a non-blank command on whitespace"
    (should= ["clj" "-M:all-tests"] (#'runner/command->argv "clj   -M:all-tests"))))

(describe "run-specs-timed"
  (it "measures elapsed time around run-specs"
    (with-redefs [runner/run-specs (fn [_ _ _] :killed)
                  runner/current-time-ms (let [values (atom [100 145])]
                                           (fn []
                                             (let [current (first @values)]
                                               (swap! values rest)
                                               current)))]
      (should= {:result :killed :elapsed-ms 45}
               (runner/run-specs-timed "clj -M:spec")))))

(run-specs)
