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
          ;; Should have symlink to deps.edn
          (should (Files/isSymbolicLink (.toPath (File. (str dir "/deps.edn")))))
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

  (it "symlinks .cpcache/ if it exists"
    (let [base-dir "target/test-workers-cpcache"
          source-rel "src/myapp/foo.cljc"
          content "(ns myapp.foo)\n"
          cpcache-dir (File. ".cpcache")]
      (let [dirs (workers/create-worker-dirs! base-dir source-rel content 1)]
        (try
          (let [cp-link (File. (str (first dirs) "/.cpcache"))]
            (if (.exists cpcache-dir)
              (should (Files/isSymbolicLink (.toPath cp-link)))
              (should-not (.exists cp-link))))
          (finally
            (workers/cleanup-worker-dirs! base-dir))))))

  (it "creates source overlay with symlinked siblings"
    (let [base-dir "target/test-workers-overlay"
          source-rel "src/clj_mutate/core.cljc"
          content "(ns clj-mutate.core)\n"
          dirs (workers/create-worker-dirs! base-dir source-rel content 1)]
      (try
        (let [dir (first dirs)
              src-dir (File. (str dir "/src"))]
          ;; src/ should be a real directory, not a symlink
          (should (.isDirectory src-dir))
          (should-not (Files/isSymbolicLink (.toPath src-dir)))
          ;; The mutated file should be a real file
          (should (.isFile (File. (str dir "/" source-rel))))
          (should-not (Files/isSymbolicLink (.toPath (File. (str dir "/" source-rel)))))
          (should= content (slurp (str dir "/" source-rel)))
          ;; Sibling source files should be symlinks
          (let [siblings (.listFiles (File. (str dir "/src/clj_mutate")))
                non-mutated (filter #(not= "core.cljc" (.getName %)) siblings)]
            (should (pos? (count non-mutated)))
            (doseq [s non-mutated]
              (should (Files/isSymbolicLink (.toPath s))))))
        (finally
          (workers/cleanup-worker-dirs! base-dir))))))

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
