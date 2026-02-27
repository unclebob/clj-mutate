(ns clj-mutate.workers-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.workers :as workers])
  (:import [java.io File]
           [java.nio.file Files]))

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
            (workers/cleanup-worker-dirs! base-dir)))))))

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
