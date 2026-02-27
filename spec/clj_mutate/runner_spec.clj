(ns clj-mutate.runner-spec
  (:require [speclj.core :refer :all]
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
          (.delete temp-dir))))))

(run-specs)
