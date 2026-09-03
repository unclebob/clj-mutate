(ns clj-mutate.coverage-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.coverage :as cov]
            [clj-mutate.project :as project]))

(def sample-lcov
  (str "SF:src/empire/combat.cljc\n"
       "DA:1,5\nDA:2,0\nDA:3,3\nDA:5,1\nend_of_record\n"))

(def other-source-lcov
  (str "SF:src/empire/other.cljc\n"
       "DA:1,1\nend_of_record\n"))

(defn temp-path [prefix suffix]
  (str "/tmp/" prefix "-" (System/nanoTime) suffix))

(defn delete-if-present! [path]
  (java.nio.file.Files/deleteIfExists (.toPath (java.io.File. path))))

(describe "lcov paths"
  (it "uses a coverage file and adjacent provenance file"
    (should= "target/coverage/lcov.info" (cov/lcov-path))
    (should= "target/coverage/clj-mutate.edn" (cov/provenance-path))))

(describe "run-coverage!"
  (it "runs the configured command and returns true on success"
    (let [calls (atom nil)]
      (with-redefs [clojure.java.shell/sh (fn [& args]
                                            (reset! calls args)
                                            {:exit 0 :out "ok" :err ""})]
        (let [result (cov/run-coverage! "clj -M:mutation-cov")]
          (should (:ok? result))
          (should= 0 (:exit result))
          (should= ["clj" "-M:mutation-cov"] @calls)))))

  (it "returns a non-ok result on failure without discarding output"
    (with-redefs [clojure.java.shell/sh (fn [& _] {:exit 7 :out "wrote lcov" :err "boom"})]
      (let [result (cov/run-coverage! "clj -M:cov")]
        (should-not (:ok? result))
        (should= 7 (:exit result))
        (should= "wrote lcov" (:out result))
        (should= "boom" (:err result))))))

(describe "load-coverage structured results"
  (it "generates missing coverage and records its profile"
    (let [lcov (temp-path "clj-mutate-lcov" ".info")
          provenance (temp-path "clj-mutate-profile" ".edn")
          ran (atom nil)]
      (try
        (with-redefs [cov/lcov-path (constantly lcov)
                      cov/provenance-path (constantly provenance)
                      cov/run-coverage! (fn [command]
                                          (reset! ran command)
                                          (spit lcov sample-lcov)
                                          true)]
          (let [result (cov/load-coverage
                         "src/empire/combat.cljc"
                         {:test-command "clj -M:mutation-spec"
                          :coverage-command "clj -M:mutation-cov"
                          :test-roots ["spec"]})]
            (should= "clj -M:mutation-cov" @ran)
            (should= :regenerated (:status result))
            (should= #{1 3 5} (:lines result))
            (should (.exists (java.io.File. provenance)))))
        (finally
          (delete-if-present! lcov)
          (delete-if-present! provenance)))))

  (it "reuses stale coverage only when provenance matches"
    (let [lcov (temp-path "clj-mutate-stale" ".info")
          command "clj -M:mutation-spec"
          coverage-command "clj -M:mutation-cov"
          expected (#'cov/expected-provenance coverage-command command ["spec"])]
      (spit lcov sample-lcov)
      (try
        (with-redefs [cov/lcov-path (constantly lcov)
                      cov/stale-reason (fn [_ _ & _] :stale)
                      cov/read-provenance (constantly expected)
                      cov/run-coverage! (fn [_] (throw (Exception. "should not run")))]
          (let [result (cov/load-coverage
                         "src/empire/combat.cljc"
                          {:reuse-lcov true
                           :test-command command
                          :coverage-command coverage-command
                          :test-roots ["spec"]})]
            (should= :stale-reused (:status result))
            (should= #{1 3 5} (:lines result))))
        (finally (delete-if-present! lcov)))))

  (it "rejects reuse when the coverage profile is unknown"
    (let [lcov (temp-path "clj-mutate-unknown-profile" ".info")]
      (spit lcov sample-lcov)
      (try
        (with-redefs [cov/lcov-path (constantly lcov)
                      cov/read-provenance (constantly nil)]
          (try
            (cov/load-coverage "src/empire/combat.cljc" {:reuse-lcov true})
            (should false)
            (catch clojure.lang.ExceptionInfo ex
              (should= :coverage-profile-mismatch (:reason (ex-data ex))))))
        (finally (delete-if-present! lcov)))))

  (it "rejects reuse when LCOV is missing"
    (let [lcov (temp-path "clj-mutate-missing" ".info")]
      (delete-if-present! lcov)
      (with-redefs [cov/lcov-path (constantly lcov)]
        (try
          (cov/load-coverage "src/empire/combat.cljc" {:reuse-lcov true})
          (should false)
          (catch clojure.lang.ExceptionInfo ex
            (should= :missing-lcov-for-reuse (:reason (ex-data ex))))))))

  (it "disables filtering when no coverage command is available"
    (let [lcov (temp-path "clj-mutate-no-coverage" ".info")]
      (with-redefs [cov/lcov-path (constantly lcov)
                    project/default-coverage-command (constantly nil)
                    project/default-test-command (constantly "bb spec")]
        (let [result (cov/load-coverage "src/empire/combat.cljc")]
          (should= :coverage-disabled (:status result))
          (should-be-nil (:lines result))))))

  (it "does not trust an existing LCOV file with unknown provenance"
    (let [lcov (temp-path "clj-mutate-bb-existing" ".info")]
      (spit lcov sample-lcov)
      (try
        (with-redefs [cov/lcov-path (constantly lcov)
                      cov/read-provenance (constantly nil)
                      project/default-coverage-command (constantly nil)
                      project/default-test-command (constantly "bb spec")]
          (let [result (cov/load-coverage "src/empire/combat.cljc")]
            (should= :coverage-disabled (:status result))
            (should-be-nil (:lines result))))
        (finally (delete-if-present! lcov)))))

  (it "regenerates stale coverage when reuse is not requested"
    (let [lcov (temp-path "clj-mutate-refresh" ".info")
          provenance (temp-path "clj-mutate-refresh" ".edn")
          ran? (atom false)
          freshness-checks (atom 0)]
      (spit lcov sample-lcov)
      (try
        (with-redefs [cov/lcov-path (constantly lcov)
                      cov/provenance-path (constantly provenance)
                      cov/stale-reason (fn [_ _ & _]
                                         (when (= 1 (swap! freshness-checks inc))
                                           :stale))
                      cov/run-coverage! (fn [_]
                                          (reset! ran? true)
                                          (spit lcov sample-lcov)
                                          true)]
          (let [result (cov/load-coverage
                         "src/empire/combat.cljc"
                         {:test-command "clj -M:spec"
                          :coverage-command "clj -M:cov"
                          :test-roots ["spec" "spec-jvm"]})]
            (should @ran?)
            (should= :regenerated (:status result))
            (should= #{1 3 5} (:lines result))))
        (finally
          (delete-if-present! lcov)
          (delete-if-present! provenance)))))

  (it "reports refresh failure without printing or trusting stale coverage"
    (let [lcov (temp-path "clj-mutate-refresh-fail" ".info")]
      (spit lcov sample-lcov)
      (try
        (with-redefs [cov/lcov-path (constantly lcov)
                      cov/stale-reason (fn [_ _ & _] :stale)
                      cov/run-coverage! (fn [_] false)]
          (let [result (atom nil)
                output (with-out-str
                         (reset! result
                                 (cov/load-coverage
                                   "src/empire/combat.cljc"
                                   {:test-command "clj -M:spec"
                                    :coverage-command "clj -M:cov"
                                    :test-roots ["spec"]})))]
            (should= "" output)
            (should= :refresh-failed (:status @result))))
        (finally (delete-if-present! lcov)))))

  (it "does not relabel an unchanged pre-existing LCOV file as regenerated"
    (let [lcov (temp-path "clj-mutate-unchanged-output" ".info")
          provenance (temp-path "clj-mutate-unchanged-output" ".edn")]
      (spit lcov sample-lcov)
      (let [before (slurp lcov)]
        (try
          (with-redefs [cov/lcov-path (constantly lcov)
                        cov/provenance-path (constantly provenance)
                        cov/stale-reason (fn [_ _ & _] :stale)
                        cov/run-coverage! (fn [_] true)]
            (let [result (cov/load-coverage
                           "src/empire/combat.cljc"
                           {:test-command "clj -M:mutation-spec"
                            :coverage-command "clj -M:mutation-cov"
                            :test-roots ["spec"]})]
              (should= :refresh-failed (:status result))
              (should-be-nil (:lines result))
              (should= before (slurp lcov))
              (should-not (.exists (java.io.File. provenance)))))
          (finally
            (delete-if-present! lcov)
            (delete-if-present! provenance))))))

  (it "treats a valid report that omits the target source as zero covered lines"
    (let [lcov (temp-path "clj-mutate-other-source" ".info")
          provenance (temp-path "clj-mutate-other-source" ".edn")]
      (try
        (with-redefs [cov/lcov-path (constantly lcov)
                      cov/provenance-path (constantly provenance)
                      cov/run-coverage! (fn [_]
                                          (spit lcov other-source-lcov)
                                          true)]
          (let [result (cov/load-coverage
                         "src/empire/combat.cljc"
                         {:test-command "clj -M:mutation-spec"
                          :coverage-command "clj -M:mutation-cov"
                          :test-roots ["spec"]})]
            (should= :regenerated (:status result))
            (should= #{} (:lines result))))
        (finally
          (delete-if-present! lcov)
          (delete-if-present! provenance)))))

  (it "restores both LCOV and provenance when new coverage is invalid"
    (let [lcov (temp-path "clj-mutate-invalid-output" ".info")
          provenance (temp-path "clj-mutate-invalid-output" ".edn")
          old-provenance {:profile :old}]
      (spit lcov sample-lcov)
      (spit provenance (pr-str old-provenance))
      (try
        (with-redefs [cov/lcov-path (constantly lcov)
                      cov/provenance-path (constantly provenance)
                      cov/run-coverage! (fn [_]
                                          (spit lcov "not an lcov report\n")
                                          true)]
          (let [result (cov/load-coverage
                         "src/empire/combat.cljc"
                         {:test-command "clj -M:mutation-spec"
                          :coverage-command "clj -M:mutation-cov"
                          :test-roots ["spec"]})]
            (should= :refresh-failed (:status result))
            (should= sample-lcov (slurp lcov))
            (should= old-provenance (read-string (slurp provenance)))))
        (finally
          (delete-if-present! lcov)
          (delete-if-present! provenance)))))

  (it "rejects coverage and test commands with different inferred roots"
    (try
      (cov/load-coverage "src/clj_mutate/coverage.cljc"
                         {:test-command "clj -M:spec"
                          :coverage-command "clj -M:property"})
      (should false)
      (catch clojure.lang.ExceptionInfo ex
        (should= :coverage-test-profile-mismatch (:reason (ex-data ex))))))

  (it "does not report regeneration when a successful command creates no LCOV file"
    (let [lcov (temp-path "clj-mutate-no-output" ".info")]
      (delete-if-present! lcov)
      (with-redefs [cov/lcov-path (constantly lcov)
                    cov/run-coverage! (fn [_] true)]
        (let [result (cov/load-coverage
                       "src/empire/combat.cljc"
                       {:test-command "true"
                        :coverage-command "true"
                        :test-roots ["spec" "spec-jvm"]})]
          (should= :missing (:status result))
          (should-be-nil (:lines result))))))

  (it "accepts a parseable LCOV even when the coverage process exits non-zero"
    (let [lcov (temp-path "clj-mutate-nonzero" ".info")
          provenance (temp-path "clj-mutate-nonzero" ".edn")]
      (try
        (with-redefs [cov/lcov-path (constantly lcov)
                      cov/provenance-path (constantly provenance)
                      cov/run-coverage! (fn [_]
                                          (spit lcov sample-lcov)
                                          {:exit 7 :out "tests failed" :err "" :ok? false})]
          (let [result (cov/load-coverage
                         "src/empire/combat.cljc"
                         {:test-command "clj -M:mutation-spec"
                          :coverage-command "clj -M:mutation-cov"
                          :test-roots ["spec"]})]
            (should= :regenerated (:status result))
            (should= #{1 3 5} (:lines result))
            (should= true (:coverage-exit-nonzero? result))
            (should= 7 (get-in result [:coverage-command-result :exit]))
            (should= "tests failed" (get-in result [:coverage-command-result :out]))))
        (finally
          (delete-if-present! lcov)
          (delete-if-present! provenance))))))

(describe "coverage-status"
  (it "reports presence, freshness, and provenance matching"
    (let [temp (java.io.File/createTempFile "lcov-status" ".info")]
      (.setLastModified temp 123)
      (with-redefs [cov/lcov-path (constantly (.getPath temp))
                    cov/newest-input-mtime (fn [_ & _] 200)
                    cov/read-provenance (constantly {:profile :old})]
        (let [status (cov/coverage-status
                       "src/empire/combat.cljc"
                       {:coverage-command "cov" :test-command "spec"})]
          (should= true (:exists? status))
          (should= 123 (:last-modified status))
          (should= :stale (:stale-reason status))
          (should= false (:profile-match? status))))
      (.delete temp))))

(describe "freshness helpers"
  (it "reports missing, stale, and fresh LCOV states"
    (let [missing (java.io.File. (temp-path "missing-lcov" ".info"))]
      (should= :missing (#'cov/stale-reason missing "src/empire/combat.cljc")))
    (let [temp (java.io.File/createTempFile "lcov" ".info")]
      (try
        (with-redefs [cov/newest-input-mtime (fn [_ & _] 200)]
          (.setLastModified temp 100)
          (should= :stale (#'cov/stale-reason temp "src/x.cljc")))
        (with-redefs [cov/newest-input-mtime (fn [_ & _] 100)]
          (.setLastModified temp 200)
          (should-be-nil (#'cov/stale-reason temp "src/x.cljc")))
        (finally (.delete temp)))))

  (it "finds the newest file in a directory tree"
    (let [root (doto (java.io.File. (temp-path "mtime-dir" "")) (.mkdirs))
          a (doto (java.io.File. root "a.txt") (spit "a"))]
      (.setLastModified a 200)
      (should= 200 (#'cov/newest-file-mtime root))
      (.delete a)
      (.delete root)))

  (it "uses explicit test-roots for freshness instead of conventional spec/"
    (let [seen (atom [])]
      (with-redefs [cov/newest-file-mtime
                    (fn [dir]
                      (swap! seen conj (.getPath dir))
                      0)]
        (#'cov/newest-input-mtime "src/foo.clj" ["test"])
        (should= ["src" "test"] @seen)))))

(run-specs)
