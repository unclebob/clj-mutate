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
      (spit deps-file "{:aliases {:spec {:extra-paths [\"spec\"]}\n           :deintroverter {:old true}}}\n")
      (try
        (with-redefs [project/running-on-babashka? (constantly false)]
          (let [before (project/test-profile-fingerprint dir "clj -M:spec")]
            (spit deps-file "{:aliases {:spec {:extra-paths [\"spec\"]}\n           :deintroverter {:new true}}}\n")
            (should= before (project/test-profile-fingerprint dir "clj -M:spec"))
            (spit spec-file "(ns changed-spec)\n")
            (should-not= before (project/test-profile-fingerprint dir "clj -M:spec"))))
        (finally
          (.delete spec-file)
          (.delete spec-dir)
          (.delete deps-file)
          (.delete (File. dir))))))

  (it "tracks custom test roots selected by a Clojure alias"
    (let [dir (str "target/test-custom-profile-" (System/nanoTime))
          test-dir (File. dir "custom-tests")
          test-file (File. test-dir "sample_spec.clj")
          deps-file (File. dir "deps.edn")]
      (.mkdirs test-dir)
      (spit test-file "(ns sample-spec)\n")
      (spit deps-file "{:aliases {:custom {:extra-paths [\"custom-tests\"]}}}\n")
      (try
        (with-redefs [project/running-on-babashka? (constantly false)]
          (let [before (project/test-profile-fingerprint dir "clj -M:custom")]
            (should= ["custom-tests"]
                     (project/test-profile-roots dir "clj -M:custom" nil))
            (spit test-file "(ns changed-sample-spec)\n")
            (should-not= before
                         (project/test-profile-fingerprint dir "clj -M:custom"))))
        (finally
          (.delete test-file)
          (.delete test-dir)
          (.delete deps-file)
          (.delete (File. dir))))))

  (it "detects mismatched inferred test and coverage populations"
    (let [dir (str "target/test-profile-match-" (System/nanoTime))
          specs (File. dir "spec")
          integration (File. dir "integration")
          deps-file (File. dir "deps.edn")]
      (.mkdirs specs)
      (.mkdirs integration)
      (spit deps-file
            "{:aliases {:mutation-spec {:extra-paths [\"spec\"]}\n           :mutation-cov {:extra-paths [\"integration\"]}}}\n")
      (try
        (should-not
          (project/commands-share-test-profile?
            dir "clj -M:mutation-spec" "clj -M:mutation-cov" nil))
        (should
          (project/commands-share-test-profile?
            dir "clj -M:mutation-spec" "clj -M:mutation-cov" ["spec"]))
        (finally
          (.delete deps-file)
          (.delete specs)
          (.delete integration)
          (.delete (File. dir))))))

  (it "rejects declared roots outside the project"
    (let [dir (str "target/test-profile-safe-root-" (System/nanoTime))]
      (.mkdirs (File. dir))
      (try
        (should= [] (project/test-profile-roots dir nil [".."]))
        (should-not
          (project/commands-share-test-profile? dir "test" "coverage" [".."]))
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
