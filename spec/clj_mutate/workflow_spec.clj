(ns clj-mutate.workflow-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.execution :as execution]
            [clj-mutate.workflow :as workflow]))

(describe "run-mutation-suite"
  (it "returns no results when there are no sites"
    (with-redefs [execution/run-mutations-parallel (fn [& _] (throw (Exception. "should not run")))]
      (should= [] (workflow/run-mutation-suite [] "src/foo.cljc" "(ns foo)" 30000 nil "clj -M:spec")))))

(describe "print-run-header"
  (it "reports line filters and missing-manifest differential runs"
    (let [header {:all-sites [{:line 3} {:line 8}]
                  :covered-sites [{:line 3}]
                  :uncovered [{:line 8}]
                  :changed-mutation-sites 2
                  :manifest-exists? false
                  :module-hash-changed? nil
                  :reuse-lcov false
                  :coverage-status {:lcov-path "target/coverage/lcov.info"
                                    :exists? false
                                    :last-modified nil
                                    :source-newer? false}
                  :surface-area-counts {:new-form-mutations 2
                                        :manifest-violating-form-mutations 0}}
          output (with-out-str
                   (workflow/print-run-header
                     "src/foo.cljc"
                     nil
                     header
                     #{3 8}
                     true
                     nil
                     false
                     [{:line 3}]
                     50))]
      (should-contain "=== Mutation Testing: src/foo.cljc ===" output)
      (should-contain "Filtering to lines: 3,8 → 1 mutations to test." output)
      (should-contain "No prior embedded manifest found; running all covered mutations." output)
      (should-contain "Manifest exists: no" output)
      (should-contain "Module hash changed: n/a" output)))

  (it "reports reuse-lcov diagnostics when last-modified is absent"
    (let [header {:all-sites []
                  :covered-sites []
                  :uncovered []
                  :changed-mutation-sites 0
                  :manifest-exists? true
                  :module-hash-changed? false
                  :reuse-lcov true
                  :coverage-status {:lcov-path "target/coverage/lcov.info"
                                    :exists? false
                                    :last-modified nil
                                    :source-newer? false}
                  :surface-area-counts {:new-form-mutations 0
                                        :manifest-violating-form-mutations 0}}
          output (with-out-str
                   (workflow/print-run-header
                     "src/foo.cljc"
                     "2026-01-01T00:00:00Z"
                     header
                     nil
                     false
                     {:module-hash "abc"}
                     true
                     []
                     50))]
      (should-contain "Previous mutation test: 2026-01-01T00:00:00Z" output)
      (should-contain "Reusing existing LCOV data from target/coverage/lcov.info." output)
      (should-contain "LCOV exists: no" output)
      (should-contain "Target source newer than LCOV: no" output)
      (should-not-contain "LCOV last modified:" output))))

(run-specs)
