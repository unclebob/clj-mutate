(ns clj-mutate.report-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.report :as report]))

(describe "print-run-header"
  (it "reports line filters"
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
                   (report/print-run-header
                     "src/foo.cljc"
                     nil
                     header
                     #{3 8}
                     false
                     nil
                     false
                     [{:line 3}]
                     50))]
      (should-contain "=== Mutation Testing: src/foo.cljc ===" output)
      (should-contain "Filtering to lines: 3,8 → 1 mutations to test." output)
      (should-contain "Manifest exists: no" output)
      (should-contain "Module hash changed: n/a" output)))

  (it "reports missing-manifest differential runs"
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
                   (report/print-run-header
                     "src/foo.cljc"
                     nil
                     header
                     nil
                     true
                     nil
                     false
                     [{:line 3}]
                     50))]
      (should-contain "No prior embedded manifest found; running all covered mutations." output)
      (should-contain "Manifest exists: no" output)))

  (it "reports reuse-lcov diagnostics when last-modified is absent"
    (let [header {:all-sites []
                  :covered-sites []
                  :uncovered []
                  :changed-mutation-sites 0
                  :manifest-exists? true
                  :module-hash-changed? false
                  :reuse-lcov true
                  :coverage-status {:status :fresh-reused
                                    :lcov-path "target/coverage/lcov.info"
                                    :exists? true
                                    :last-modified 0
                                    :source-newer? false}
                  :surface-area-counts {:new-form-mutations 0
                                        :manifest-violating-form-mutations 0}}
          output (with-out-str
                   (report/print-run-header
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
      (should-contain "Reusing fresh LCOV from target/coverage/lcov.info" output)
      (should-not-contain "stale" output)))

  (it "does not report LCOV covered/uncovered counts when coverage is disabled"
    (let [header {:all-sites [{:line 3} {:line 8}]
                  :covered-sites [{:line 3} {:line 8}]
                  :uncovered []
                  :changed-mutation-sites 2
                  :manifest-exists? false
                  :module-hash-changed? nil
                  :reuse-lcov false
                  :coverage-status {:status :coverage-disabled}
                  :surface-area-counts {:new-form-mutations 2
                                        :manifest-violating-form-mutations 0}}
          output (with-out-str
                   (report/print-run-header
                     "src/foo.cljc"
                     nil
                     header
                     nil
                     false
                     nil
                     false
                     [{:line 3} {:line 8}]
                     50))]
      (should-contain "Coverage filtering disabled; 2 located sites will be tested." output)
      (should-not-contain "Covered mutation sites:" output)
      (should-not-contain "Uncovered mutation sites:" output)))

  (it "prints coverage command output after a failed generation"
    (let [header {:all-sites []
                  :covered-sites []
                  :uncovered []
                  :changed-mutation-sites 0
                  :manifest-exists? false
                  :module-hash-changed? nil
                  :reuse-lcov false
                  :coverage-status {:status :missing
                                    :coverage-command-result {:exit 7 :out "cloverage boom\n" :err "exit 7\n" :ok? false}}
                  :surface-area-counts {:new-form-mutations 0
                                        :manifest-violating-form-mutations 0}}
          output (with-out-str
                   (report/print-run-header
                     "src/foo.cljc"
                     nil
                     header
                     nil
                     false
                     nil
                     false
                     []
                     50))]
      (should-contain "Coverage data is missing and no usable LCOV file was generated." output)
      (should-contain "Coverage command exited 7." output)
      (should-contain "cloverage boom" output)
      (should-contain "exit 7" output))))

(describe "print-uncovered"
  (it "prints coverage gaps when uncovered mutations exist"
    (let [output (with-out-str
                   (report/print-uncovered [{:line 12 :description "if -> if-not"}
                                            {:line 15 :description "0 -> 1"}]))]
      (should-contain "=== Coverage Gaps (2 mutations on uncovered lines) ===" output)
      (should-contain "12:0  if -> if-not" output)
      (should-contain "15:0  0 -> 1" output))))

(describe "format-report"
  (it "produces summary with kill count"
    (let [results [{:site {:description "+ -> -"} :result :killed}
                   {:site {:description "1 -> 0"} :result :survived}]
          formatted (report/format-report "src/empire/foo.cljc" results 0)]
      (should-contain "1/2 mutants killed" formatted)
      (should-contain "SURVIVED" formatted)
      (should-contain "KILLED" formatted)))

  (it "includes line numbers in progress lines"
    (let [results [{:site {:description "+ -> -" :line 42} :result :killed}
                   {:site {:description "1 -> 0" :line 99 :index 1} :result :survived}]
          formatted (report/format-report "src/empire/foo.cljc" results 0)]
      (should-contain "42:0" formatted)
      (should-contain "99:0" formatted)))

  (it "includes line numbers in survivor summary"
    (let [results [{:site {:description "1 -> 0" :line 207 :index 0} :result :survived}]
          formatted (report/format-report "src/empire/foo.cljc" results 0)]
      (should-contain "207:0" formatted)))

  (it "includes uncovered count in summary"
    (let [results [{:site {:description "+ -> -"} :result :killed}]
          formatted (report/format-report "src/empire/foo.cljc" results 3)]
      (should-contain "1/1 mutants killed" formatted)
      (should-contain "3 uncovered" formatted))))

(run-specs)
