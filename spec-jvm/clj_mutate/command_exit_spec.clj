(ns clj-mutate.command-exit-spec
  (:require [speclj.core :refer :all]
            [clojure.java.shell :as shell]))

(describe "command exit statuses"
  (tags :no-mutate)

  (it "returns 2 for a failing baseline and 3 for surviving mutants"
    (let [path (str "target/exit-status-" (System/nanoTime) ".cljc")]
      (spit path "(ns exit-status-fixture)\n(defn value [] (+ 1 2))\n")
      (try
        (let [baseline (shell/sh "clj" "-M:mutate" path
                                 "--no-coverage" "--test-command" "false")
              survivors (shell/sh "clj" "-M:mutate" path
                                  "--no-coverage" "--test-command" "true"
                                  "--max-workers" "1")]
          (should= 2 (:exit baseline))
          (should= 3 (:exit survivors)))
        (finally
          (java.nio.file.Files/deleteIfExists
            (.toPath (java.io.File. path))))))))

(run-specs)
