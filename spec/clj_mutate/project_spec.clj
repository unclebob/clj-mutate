(ns clj-mutate.project-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.project :as project])
  (:import [java.io File]))

(describe "bb-project?"
  (it "returns true when bb.edn exists in the directory"
    (let [dir (str "target/test-project-bb-" (System/nanoTime))]
      (.mkdirs (File. dir))
      (try
        (spit (str dir "/bb.edn") "{}")
        (should (project/bb-project? dir))
        (finally
          (.delete (File. (str dir "/bb.edn")))
          (.delete (File. dir))))))

  (it "returns false when bb.edn does not exist"
    (let [dir (str "target/test-project-clj-" (System/nanoTime))]
      (.mkdirs (File. dir))
      (try
        (should-not (project/bb-project? dir))
        (finally
          (.delete (File. dir)))))))

(describe "spec-command"
  (it "returns bb spec for bb projects"
    (let [dir (str "target/test-project-cmd-" (System/nanoTime))]
      (.mkdirs (File. dir))
      (try
        (spit (str dir "/bb.edn") "{}")
        (should= ["bb" "spec"] (project/spec-command dir))
        (finally
          (.delete (File. (str dir "/bb.edn")))
          (.delete (File. dir))))))

  (it "returns clj -M:spec for deps.edn projects"
    (let [dir (str "target/test-project-cmd2-" (System/nanoTime))]
      (.mkdirs (File. dir))
      (try
        (should= ["clj" "-M:spec"] (project/spec-command dir))
        (finally
          (.delete (File. dir)))))))

(describe "config-file"
  (it "returns bb.edn for bb projects"
    (let [dir (str "target/test-project-cfg-" (System/nanoTime))]
      (.mkdirs (File. dir))
      (try
        (spit (str dir "/bb.edn") "{}")
        (should= "bb.edn" (project/config-file dir))
        (finally
          (.delete (File. (str dir "/bb.edn")))
          (.delete (File. dir))))))

  (it "returns deps.edn for deps.edn projects"
    (let [dir (str "target/test-project-cfg2-" (System/nanoTime))]
      (.mkdirs (File. dir))
      (try
        (should= "deps.edn" (project/config-file dir))
        (finally
          (.delete (File. dir)))))))

(run-specs)
