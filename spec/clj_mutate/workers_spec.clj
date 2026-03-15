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

  (it "symlinks shared project directories into worker roots"
    (let [base-dir "target/test-workers-links"
          source-rel "src/myapp/foo.cljc"
          content "(ns myapp.foo)\n"
          cpcache-dir (File. ".cpcache")]
      (let [dirs (workers/create-worker-dirs! base-dir source-rel content 1)]
        (try
          (doseq [[entry expected-link?]
                  [["spec" true]
                   [".cpcache" (.exists cpcache-dir)]]]
            (should= expected-link?
                     (Files/isSymbolicLink (.toPath (File. (str (first dirs) "/" entry))))))
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
