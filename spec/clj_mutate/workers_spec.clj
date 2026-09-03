(ns clj-mutate.workers-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.project :as project]
            [clj-mutate.workers :as workers])
  (:import [java.io File]
           [java.nio.file Files]))

(describe "new-run-base-dir"
  (it "creates unique run directories under the root"
    (let [root "target/mutation-workers"
          a (workers/new-run-base-dir root)
          b (workers/new-run-base-dir root)]
      (should (.startsWith a (str root "/run-")))
      (should (.startsWith b (str root "/run-")))
      (should-not= a b))))

(describe "create-worker-dirs!"
  (it "creates N worker directories under base-dir"
    (let [base-dir "target/test-workers"
          source-rel "src/myapp/foo.cljc"
          original-content "(ns myapp.foo)\n(defn bar [] (+ 1 2))\n"
          dirs (workers/create-worker-dirs! base-dir source-rel original-content 2)]
      (try
        (should= 2 (count dirs))
        (doseq [dir dirs]
          (should (.exists (File. dir)))
          (doseq [config (filter #(.exists (File. %)) ["bb.edn" "deps.edn"])]
            (should (.exists (File. (str dir "/" config))))
            (should-not (Files/isSymbolicLink (.toPath (File. (str dir "/" config))))))
          (doseq [test-dir (project/test-directories)]
            (should (Files/isSymbolicLink
                      (.toPath (File. (str dir "/" test-dir))))))
          (should= original-content (slurp (str dir "/" source-rel))))
        (finally
          (workers/cleanup-worker-dirs! base-dir)))))

  (it "does not symlink cache directories"
    (let [base-dir "target/test-workers-cache"
          source-rel "src/myapp/foo.cljc"
          content "(ns myapp.foo)\n"
          dirs (workers/create-worker-dirs! base-dir source-rel content 1)]
      (try
        (let [dir (first dirs)]
          (should-not (.exists (File. (str dir "/.cpcache"))))
          (should-not (.exists (File. (str dir "/.babashka")))))
        (finally
          (workers/cleanup-worker-dirs! base-dir)))))

  (it "creates source overlay with symlinked siblings"
    (let [base-dir "target/test-workers-overlay"
          temp-root (str "target/test-overlay-root-" (System/nanoTime))
          source-rel "src/myns/target.cljc"
          content "(ns myns.target)\n"]
      (.mkdirs (File. (str temp-root "/src/myns")))
      (spit (str temp-root "/src/myns/target.cljc") "(ns myns.target :original)\n")
      (spit (str temp-root "/src/myns/sibling_a.cljc") "(ns myns.sibling-a)\n")
      (spit (str temp-root "/src/myns/sibling_b.cljc") "(ns myns.sibling-b)\n")
      (let [prev-dir (System/getProperty "user.dir")]
        (try
          (System/setProperty "user.dir" temp-root)
          (let [worker-path (str base-dir "/worker-0")]
            (.mkdirs (File. worker-path))
            (#'workers/setup-source-overlay! worker-path temp-root source-rel content)
            (let [src-dir (File. (str worker-path "/src"))]
              (should (.isDirectory src-dir))
              (should-not (Files/isSymbolicLink (.toPath src-dir)))
              (should (.isFile (File. (str worker-path "/" source-rel))))
              (should-not (Files/isSymbolicLink (.toPath (File. (str worker-path "/" source-rel)))))
              (should= content (slurp (str worker-path "/" source-rel)))
              (let [siblings (.listFiles (File. (str worker-path "/src/myns")))
                    non-target (filter #(not= "target.cljc" (.getName %)) siblings)]
                (should= 2 (count non-target))
                (doseq [s non-target]
                  (should (Files/isSymbolicLink (.toPath s)))))))
          (finally
            (System/setProperty "user.dir" prev-dir)
            (workers/cleanup-worker-dirs! base-dir)
            (#'workers/delete-recursive! (File. temp-root))))))))

(describe "cleanup-worker-dirs!"
  (it "removes the base directory and all contents"
    (let [base-dir "target/test-workers-cleanup"
          source-rel "src/myapp/foo.cljc"
          content "(ns myapp.foo)\n"]
      (workers/create-worker-dirs! base-dir source-rel content 2)
      (should (.exists (File. base-dir)))
      (workers/cleanup-worker-dirs! base-dir)
      (should-not (.exists (File. base-dir))))))

(run-specs)
