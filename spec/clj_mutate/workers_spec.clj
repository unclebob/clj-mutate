(ns clj-mutate.workers-spec
  (:require [speclj.core :refer :all]
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
          ;; Should have a copy of the project config (not a symlink)
          (let [config (if (.exists (File. "bb.edn")) "bb.edn" "deps.edn")]
            (should (.exists (File. (str dir "/" config))))
            (should-not (Files/isSymbolicLink (.toPath (File. (str dir "/" config))))))
          ;; Should have source file with original content
          (should= original-content (slurp (str dir "/" source-rel))))
        (finally
          (workers/cleanup-worker-dirs! base-dir)))))

  (it "symlinks spec/ directory"
    (let [base-dir "target/test-workers-spec"
          source-rel "src/myapp/foo.cljc"
          content "(ns myapp.foo)\n"
          dirs (workers/create-worker-dirs! base-dir source-rel content 1)]
      (try
        (let [spec-path (.toPath (File. (str (first dirs) "/spec")))]
          (should (Files/isSymbolicLink spec-path)))
        (finally
          (workers/cleanup-worker-dirs! base-dir)))))

  (it "does not symlink cache directories (cached classpaths would bypass source overlay)"
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
    ;; Use a temp directory as the project root to avoid writing through
    ;; symlinks to real project files during mutation testing
    (let [base-dir "target/test-workers-overlay"
          temp-root (str "target/test-overlay-root-" (System/nanoTime))
          source-rel "src/myns/target.cljc"
          content "(ns myns.target)\n"]
      ;; Create a fake project source tree
      (.mkdirs (File. (str temp-root "/src/myns")))
      (spit (str temp-root "/src/myns/target.cljc") "(ns myns.target :original)")
      (spit (str temp-root "/src/myns/sibling_a.cljc") "(ns myns.sibling-a)")
      (spit (str temp-root "/src/myns/sibling_b.cljc") "(ns myns.sibling-b)")
      (try
        (let [worker-path (str base-dir "/worker-0")]
          (.mkdirs (File. worker-path))
          (#'workers/setup-source-overlay! worker-path temp-root source-rel content)
          (let [src-dir (File. (str worker-path "/src"))]
            ;; src/ should be a real directory, not a symlink
            (should (.isDirectory src-dir))
            (should-not (Files/isSymbolicLink (.toPath src-dir)))
            ;; The target file should be a real file with overlay content
            (should (.isFile (File. (str worker-path "/" source-rel))))
            (should-not (Files/isSymbolicLink (.toPath (File. (str worker-path "/" source-rel)))))
            (should= content (slurp (str worker-path "/" source-rel)))
            ;; Sibling source files should be symlinks
            (let [siblings (.listFiles (File. (str worker-path "/src/myns")))
                  non-target (filter #(not= "target.cljc" (.getName %)) siblings)]
              (should= 2 (count non-target))
              (doseq [s non-target]
                (should (Files/isSymbolicLink (.toPath s)))))))
        (finally
          (workers/cleanup-worker-dirs! base-dir)
          (#'workers/delete-recursive! (File. temp-root)))))))

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
