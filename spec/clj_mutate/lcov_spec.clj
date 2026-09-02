(ns clj-mutate.lcov-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.lcov :as lcov]))

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
    (let [result (lcov/parse-lcov sample-lcov)]
      (should= #{1 3 5} (get result "src/empire/combat.cljc"))
      (should= #{11} (get result "src/empire/game_loop.cljc"))))

  (it "handles empty input and ignorable LCOV lines"
    (doseq [[lcov-text assertions]
            [[""
              [#(should= {} %)]]
             ["SF:foo.cljc\nDA:1,0\nDA:2,0\nend_of_record\n"
              [#(should= #{} (get % "foo.cljc"))]]
             ["DA:1,3\nSF:foo.cljc\nDA:2,4\nend_of_record\n"
              [#(should= nil (get % nil))
               #(should= #{2} (get % "foo.cljc"))]]
             ["TN:\nSF:foo.cljc\nFN:1,bar\nDA:2,1\nend_of_record\n"
              [#(should= #{2} (get % "foo.cljc"))]]
             ["SF:foo.cljc\nDA:1,1\nDA:2,0\nend_of_record\nDA:9,7\n"
              [#(should= #{1} (get % "foo.cljc"))]]]]
      (let [result (lcov/parse-lcov lcov-text)]
        (should (seq assertions))
        (doseq [assertion assertions]
          (assertion result))))))

(describe "covered-lines"
  (it "returns covered lines for exact path match"
    (let [lcov-map {"src/empire/combat.cljc" #{1 3 5}}]
      (should= #{1 3 5} (lcov/covered-lines lcov-map "src/empire/combat.cljc"))))

  (it "returns covered lines for suffix match"
    (let [lcov-map {"/abs/path/src/empire/combat.cljc" #{1 3 5}}]
      (should= #{1 3 5} (lcov/covered-lines lcov-map "src/empire/combat.cljc"))))

  (it "returns nil when no match found"
    (should-be-nil (lcov/covered-lines {} "src/empire/combat.cljc"))))

(run-specs)
