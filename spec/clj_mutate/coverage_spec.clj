(ns clj-mutate.coverage-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.coverage :as cov]))

(def sample-lcov
  (str "SF:src/empire/combat.cljc\n"
       "DA:1,5\n"
       "DA:2,0\n"
       "DA:3,3\n"
       "DA:5,1\n"
       "end_of_record\n"
       "SF:src/empire/game_loop.cljc\n"
       "DA:10,0\n"
       "DA:11,2\n"
       "end_of_record\n"))

(describe "parse-lcov"
  (it "parses LCOV text into map of file to covered line set"
    (let [result (cov/parse-lcov sample-lcov)]
      (should= #{1 3 5} (get result "src/empire/combat.cljc"))
      (should= #{11} (get result "src/empire/game_loop.cljc"))))

  (it "returns empty map for empty input"
    (should= {} (cov/parse-lcov "")))

  (it "excludes lines with zero count"
    (let [result (cov/parse-lcov "SF:foo.cljc\nDA:1,0\nDA:2,0\nend_of_record\n")]
      (should= #{} (get result "foo.cljc")))))

(describe "covered-lines"
  (it "returns covered lines for exact path match"
    (let [lcov-map {"src/empire/combat.cljc" #{1 3 5}}]
      (should= #{1 3 5} (cov/covered-lines lcov-map "src/empire/combat.cljc"))))

  (it "returns covered lines for suffix match"
    (let [lcov-map {"/abs/path/src/empire/combat.cljc" #{1 3 5}}]
      (should= #{1 3 5} (cov/covered-lines lcov-map "src/empire/combat.cljc"))))

  (it "returns nil when no match found"
    (should-be-nil (cov/covered-lines {} "src/empire/combat.cljc"))))

(describe "lcov-path"
  (it "returns the expected path"
    (should= "target/coverage/lcov.info" (cov/lcov-path))))

(describe "run-coverage!"
  (it "runs clj -M:cov --lcov and returns true on success"
    (let [calls (atom nil)]
      (with-redefs [clojure.java.shell/sh (fn [& args]
                                            (reset! calls args)
                                            {:exit 0 :out "" :err ""})]
        (should (cov/run-coverage!))
        (should= ["clj" "-M:cov" "--lcov"] @calls))))

  (it "returns false on failure"
    (with-redefs [clojure.java.shell/sh (fn [& _] {:exit 1 :out "" :err ""})]
      (should-not (cov/run-coverage!)))))

(describe "load-coverage"
  (it "runs coverage when lcov.info is missing"
    (let [ran? (atom false)
          result (atom nil)
          temp-lcov (str "/tmp/test-lcov-" (System/nanoTime) ".info")]
      (with-redefs [cov/run-coverage! (fn [] (reset! ran? true)
                                        (spit temp-lcov sample-lcov)
                                        true)
                    cov/lcov-path (constantly temp-lcov)
                    clj-mutate.project/bb-project? (constantly false)]
        (java.nio.file.Files/deleteIfExists
          (.toPath (java.io.File. temp-lcov)))
        (with-out-str (reset! result (cov/load-coverage "src/empire/combat.cljc")))
        (should @ran?)
        (should= #{1 3 5} @result))))

  (it "returns nil when lcov.info does not exist and coverage fails"
    (let [result (atom nil)
          temp-lcov (str "/tmp/nonexistent-lcov-" (System/nanoTime) ".info")]
      (with-redefs [cov/run-coverage! (fn [] false)
                    cov/lcov-path (constantly temp-lcov)]
        (java.nio.file.Files/deleteIfExists
          (.toPath (java.io.File. temp-lcov)))
        (with-out-str (reset! result (cov/load-coverage "src/empire/combat.cljc")))
        (should-be-nil @result)))))

(run-specs)
