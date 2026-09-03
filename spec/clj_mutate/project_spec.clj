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
  (it "returns bb spec --tag ~no-mutate when running on Babashka"
    (with-redefs [project/running-on-babashka? (constantly true)]
      (should= ["bb" "spec" "--tag" "~no-mutate"] (project/spec-command))))

  (it "returns clj -M:spec --tag ~no-mutate when running on the JVM"
    (with-redefs [project/running-on-babashka? (constantly false)]
      (should= ["clj" "-M:spec" "--tag" "~no-mutate"] (project/spec-command)))))

(describe "runtime test profile"
  (it "selects the common and JVM-specific test roots on the JVM"
    (with-redefs [project/running-on-babashka? (constantly false)]
      (should= ["spec" "spec-jvm"] (project/test-directories))))

  (it "selects the common and Babashka-specific test roots under Babashka"
    (with-redefs [project/running-on-babashka? (constantly true)]
      (should= ["spec" "spec-bb"] (project/test-directories))))

  (it "fingerprints effective tests but ignores unrelated deps.edn changes"
    (let [dir (str "target/test-profile-" (System/nanoTime))
          spec-dir (File. dir "spec")
          spec-file (File. spec-dir "sample_spec.clj")
          deps-file (File. dir "deps.edn")]
      (.mkdirs spec-dir)
      (spit spec-file "(ns sample-spec)\n")
      (spit deps-file "{:aliases {:deintroverter {:old true}}}\n")
      (try
        (with-redefs [project/running-on-babashka? (constantly false)]
          (let [before (project/test-profile-fingerprint dir "clj -M:spec")]
            (spit deps-file "{:aliases {:deintroverter {:new true}}}\n")
            (should= before (project/test-profile-fingerprint dir "clj -M:spec"))
            (spit spec-file "(ns changed-spec)\n")
            (should-not= before (project/test-profile-fingerprint dir "clj -M:spec"))))
        (finally
          (.delete spec-file)
          (.delete spec-dir)
          (.delete deps-file)
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

(describe "config-files"
  (it "returns both configs when both exist"
    (let [dir (str "target/test-project-cfgs-" (System/nanoTime))]
      (.mkdirs (File. dir))
      (try
        (spit (str dir "/bb.edn") "{}")
        (spit (str dir "/deps.edn") "{}")
        (should= ["bb.edn" "deps.edn"] (project/config-files dir))
        (finally
          (.delete (File. (str dir "/bb.edn")))
          (.delete (File. (str dir "/deps.edn")))
          (.delete (File. dir))))))

  (it "returns only the files that exist"
    (let [dir (str "target/test-project-cfg-one-" (System/nanoTime))]
      (.mkdirs (File. dir))
      (try
        (spit (str dir "/deps.edn") "{}")
        (should= ["deps.edn"] (project/config-files dir))
        (finally
          (.delete (File. (str dir "/deps.edn")))
          (.delete (File. dir)))))))

(run-specs)
