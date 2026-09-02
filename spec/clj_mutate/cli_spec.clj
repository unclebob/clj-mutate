(ns clj-mutate.cli-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.cli :as cli]
            [clj-mutate.project :as project]))

(describe "validate-args"
  (defn with-temp-source-path [f]
    (let [temp (java.io.File/createTempFile "src" ".cljc")
          temp-path (.getPath temp)]
      (spit temp "(ns test-ns)")
      (try
        (f temp-path)
        (finally
          (java.nio.file.Files/deleteIfExists (.toPath temp))))))

  (it "returns the right validation errors"
    (with-temp-source-path
      (fn [temp-source-path]
        (doseq [[args expected]
                [[[] :missing-source]
                 [["--help"] :help]
                 [["nonexistent.cljc"] :missing-file]
                 [[temp-source-path "--bogus"] "Unknown option: --bogus"]
                 [[temp-source-path "extra.cljc"] "Unexpected extra argument: extra.cljc"]
                 [[temp-source-path "--lines"] "Missing value for --lines."]
                 [[temp-source-path "--timeout-factor"] "Missing value for --timeout-factor."]
                 [[temp-source-path "--test-command"] "Missing value for --test-command."]
                 [[temp-source-path "--max-workers"] "Missing value for --max-workers."]
                 [[temp-source-path "--mutation-warning"] "Missing value for --mutation-warning."]
                 [[temp-source-path "--timeout-factor" "0"] :error]
                 [[temp-source-path "--test-command" "   "] "Missing value for --test-command."]
                 [[temp-source-path "--max-workers" "0"] :error]
                 [[temp-source-path "--mutation-warning" "0"] :error]]]
          (let [result (cli/validate-args args)]
            (case expected
              :missing-source (should-contain :error result)
              :help (do
                      (should= true (:help result))
                      (should-contain "Usage:" (:usage result)))
              :missing-file (should-contain :error result)
              :error (should-contain :error result)
              (should= expected (:error result))))))))

  (it "parses supported option values"
    (with-temp-source-path
      (fn [temp-source-path]
        (doseq [[args assertions]
                [[[temp-source-path]
                  [#(should= temp-source-path (:source-path %))
                   #(should= 10 (:timeout-factor %))
                   #(should= (project/default-test-command) (:test-command %))
                   #(should= false (:since-last-run %))
                   #(should= false (:mutate-all %))
                   #(should= 100 (:mutation-warning %))
                   #(should= nil (:max-workers %))]]
                 [[temp-source-path "--since-last-run"]
                  [#(should= true (:since-last-run %))]]
                 [[temp-source-path "--mutate-all"]
                  [#(should= true (:mutate-all %))]]
                 [[temp-source-path "--scan"]
                  [#(should= true (:scan %))]]
                 [[temp-source-path "--update-manifest"]
                  [#(should= true (:update-manifest %))]]
                 [[temp-source-path "--reuse-lcov"]
                  [#(should= true (:reuse-lcov %))]]
                 [[temp-source-path "--mutation-warning" "75"]
                  [#(should= 75 (:mutation-warning %))]]
                 [[temp-source-path "--timeout-factor" "7"]
                  [#(should= 7 (:timeout-factor %))]]
                 [[temp-source-path "--test-command" "clj -M:all-tests"]
                  [#(should= "clj -M:all-tests" (:test-command %))]]
                 [[temp-source-path "--max-workers" "3"]
                  [#(should= 3 (:max-workers %))]]]]
          (let [result (cli/validate-args args)]
            (should (seq assertions))
            (doseq [assertion assertions]
              (assertion result)))))))

  (it "rejects incompatible option combinations"
    (with-temp-source-path
      (fn [temp-source-path]
        (doseq [args [[temp-source-path "--lines" "3" "--since-last-run"]
                      [temp-source-path "--since-last-run" "--lines" "3"]
                      [temp-source-path "--mutate-all" "--lines" "3"]
                      [temp-source-path "--since-last-run" "--mutate-all"]
                      [temp-source-path "--scan" "--lines" "3"]
                      [temp-source-path "--scan" "--since-last-run"]
                      [temp-source-path "--scan" "--mutate-all"]
                      [temp-source-path "--scan" "--timeout-factor" "7"]
                      [temp-source-path "--scan" "--test-command" "clj -M:all-tests"]
                      [temp-source-path "--scan" "--max-workers" "2"]
                      [temp-source-path "--update-manifest" "--scan"]
                      [temp-source-path "--update-manifest" "--lines" "3"]
                      [temp-source-path "--update-manifest" "--since-last-run"]
                      [temp-source-path "--update-manifest" "--mutate-all"]
                      [temp-source-path "--update-manifest" "--timeout-factor" "7"]
                      [temp-source-path "--update-manifest" "--test-command" "clj -M:all-tests"]
                      [temp-source-path "--update-manifest" "--max-workers" "2"]]]
          (should-contain :error (cli/validate-args args)))))))

(run-specs)
