(ns clj-mutate.core-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.core :as core]
            [clj-mutate.workflow :as workflow]))

(describe "handle-main-result"
  (it "prints help without exiting"
    (let [output (with-out-str (#'core/handle-main-result {:help true :usage "Usage text"}))]
      (should-contain "Usage text" output)))

  (it "prints errors and returns a configuration failure"
    (let [result (atom nil)
          output (with-out-str
                   (reset! result
                           (#'core/handle-main-result {:error "Bad args" :usage "Usage text"})))]
      (should= :configuration-error (:status @result))
      (should-contain "Bad args" output)
      (should-contain "Usage text" output)))

  (it "dispatches to scan-mutation-sites for scan input"
    (let [received (atom nil)]
      (with-redefs [workflow/scan-mutation-sites (fn [source-path mutation-warning]
                                                   (reset! received {:source-path source-path
                                                                     :mutation-warning mutation-warning}))]
        (#'core/handle-main-result {:source-path "src/foo.cljc"
                                    :scan true
                                    :mutation-warning 75})
        (should= {:source-path "src/foo.cljc"
                  :mutation-warning 75}
                 @received))))

  (it "dispatches to update-manifest! for update-manifest input"
    (let [received (atom nil)]
      (with-redefs [workflow/update-manifest! (fn [source-path]
                                                (reset! received source-path))]
        (#'core/handle-main-result {:source-path "src/foo.cljc"
                                    :update-manifest true})
        (should= "src/foo.cljc" @received))))

  (it "dispatches to run-mutation-testing for valid input"
    (let [received (atom nil)]
      (with-redefs [workflow/run-mutation-testing
                    (fn [source-path lines timeout-factor test-command max-workers since-last-run mutate-all mutation-warning reuse-lcov mutation coverage-command]
                      (reset! received {:source-path source-path
                                        :lines lines
                                        :timeout-factor timeout-factor
                                        :test-command test-command
                                        :max-workers max-workers
                                        :since-last-run since-last-run
                                        :mutate-all mutate-all
                                        :mutation-warning mutation-warning
                                        :reuse-lcov reuse-lcov
                                        :mutation mutation
                                        :coverage-command coverage-command}))]
        (#'core/handle-main-result {:source-path "src/foo.cljc"
                                    :lines #{3}
                                    :timeout-factor 7
                                    :test-command "clj -M:all-tests"
                                    :max-workers 2
                                    :since-last-run true
                                    :mutate-all false
                                    :mutation-warning 75
                                    :reuse-lcov true
                                    :mutation "M002"
                                    :coverage-command nil})
        (should= {:source-path "src/foo.cljc"
                  :lines #{3}
                  :timeout-factor 7
                  :test-command "clj -M:all-tests"
                  :max-workers 2
                  :since-last-run true
                  :mutate-all false
                  :mutation-warning 75
                  :reuse-lcov true
                  :mutation "M002"
                  :coverage-command nil}
                 @received))))

  (it "prints a helpful error when reuse-lcov is requested without an lcov file"
    (let [result (atom nil)
          output (with-out-str
                   (with-redefs [workflow/run-mutation-testing
                                 (fn [& _]
                                   (throw (ex-info "missing lcov"
                                                   {:reason :missing-lcov-for-reuse
                                                    :lcov-path "target/coverage/lcov.info"})))]
                     (reset! result
                             (#'core/handle-main-result {:source-path "src/foo.cljc"
                                                        :lines nil
                                                        :timeout-factor 10
                                                        :test-command "clj -M:spec"
                                                        :max-workers nil
                                                        :since-last-run false
                                                        :mutate-all false
                                                        :mutation-warning 50
                                                        :reuse-lcov true}))))]
      (should= :configuration-error (:status @result))
      (should-contain "Error: --reuse-lcov was requested, but target/coverage/lcov.info does not exist." output)
      (should-contain "Run without --reuse-lcov once to generate coverage." output))))

(describe "-main"
  (it "shuts down agents after successful command handling"
    (let [handled (atom nil)
          shutdowns (atom 0)]
      (with-redefs [clj-mutate.cli/validate-args (fn [args] {:validated args})
                    core/handle-main-result (fn [validated]
                                              (reset! handled validated)
                                              {:status :passed})
                    core/shutdown-runtime! (fn [] (swap! shutdowns inc))]
        (core/-main "src/foo.cljc" "--scan")
        (should= {:validated ["src/foo.cljc" "--scan"]} @handled)
        (should= 1 @shutdowns))))

  (it "shuts down agents when command handling throws"
    (let [shutdowns (atom 0)]
      (should-throw Exception
                    (with-redefs [clj-mutate.cli/validate-args (fn [_] {:source-path "src/foo.cljc"})
                                  core/handle-main-result (fn [_] (throw (Exception. "boom")))
                                  core/shutdown-runtime! (fn [] (swap! shutdowns inc))]
                      (core/-main "src/foo.cljc")))
      (should= 1 @shutdowns)))

  (it "maps baseline failures and survivors to command exit codes"
    (let [statuses (atom [])]
      (with-redefs [clj-mutate.cli/validate-args (fn [args] (apply hash-map args))
                    core/handle-main-result (fn [validated] validated)
                    core/shutdown-runtime! (fn [] nil)
                    core/exit! (fn [status] (swap! statuses conj status))]
        (core/-main :status :baseline-failed)
        (core/-main :status :survivors))
      (should= [2 3] @statuses))))

(run-specs)
