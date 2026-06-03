(ns clj-mutate.runner-bb-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.runner :as runner])
  (:import [java.io File]))

(describe "run-specs under Babashka"
  (it "accepts a timeout-ms and dir parameter"
    (let [temp-dir (doto (File. (str "target/test-runner-bb-" (System/nanoTime)))
                     (.mkdirs))
          dir-path (.getPath temp-dir)]
      (try
        (should-not-throw
          (try (runner/run-specs 100 dir-path)
               (catch Exception e
                 (when (instance? clojure.lang.ArityException e)
                   (throw e)))))
        (finally
          (.delete temp-dir)))))

  (it "returns survived when the process exits zero"
    (should= :survived (runner/run-specs 1000 nil "true")))

  (it "returns killed when the process exits non-zero"
    (should= :killed (runner/run-specs 1000 nil "false")))

  (it "returns timeout when the process does not finish in time"
    (should= :timeout (runner/run-specs 10 nil "sleep 1"))))

(describe "command->argv under Babashka"
  (it "falls back to the default command when input is blank"
    (should= ["clj" "-M:spec" "--tag" "~no-mutate"] (#'runner/command->argv "   ")))

  (it "splits a non-blank command on whitespace"
    (should= ["bb" "spec-bb/clj_mutate/runner_bb_spec.clj"]
             (#'runner/command->argv "bb   spec-bb/clj_mutate/runner_bb_spec.clj"))))

(describe "run-specs-timed under Babashka"
  (it "measures elapsed time around run-specs"
    (with-redefs [runner/run-specs (fn [_ _ _] :killed)
                  runner/current-time-ms (let [values (atom [100 145])]
                                           (fn []
                                             (let [current (first @values)]
                                               (swap! values rest)
                                               current)))]
      (should= {:result :killed :elapsed-ms 45}
               (runner/run-specs-timed "false")))))

(run-specs)
