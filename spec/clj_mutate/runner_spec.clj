(ns clj-mutate.runner-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.runner :as runner]))

(describe "source->spec-path"
  (it "maps root source to spec"
    (should= "spec/empire/combat_spec.clj"
             (runner/source->spec-path "src/empire/combat.cljc")))

  (it "maps subdirectory source to spec"
    (should= "spec/empire/computer/ship_spec.clj"
             (runner/source->spec-path "src/empire/computer/ship.cljc")))

  (it "handles deeply nested paths"
    (should= "spec/empire/movement/map_utils_spec.clj"
             (runner/source->spec-path "src/empire/movement/map_utils.cljc"))))

(describe "spec-exists?"
  (it "returns true for existing spec"
    (should (runner/spec-exists? "spec/clj_mutate/runner_spec.clj")))

  (it "returns false for nonexistent spec"
    (should-not (runner/spec-exists? "spec/clj_mutate/nonexistent_spec.clj"))))

(describe "run-spec"
  (it "returns :killed when spec fails (real spec with syntax error)"
    (spit "/tmp/failing_spec.clj"
          "(ns failing-spec (:require [speclj.core :refer :all]))
           (describe \"fail\" (it \"fails\" (should= 1 2)))
           (run-specs)")
    (should= :killed (runner/run-spec "/tmp/failing_spec.clj")))

  (it "returns :survived when spec passes (real spec)"
    (spit "/tmp/passing_spec.clj"
          "(ns passing-spec (:require [speclj.core :refer :all]))
           (describe \"pass\" (it \"passes\" (should= 1 1)))
           (run-specs)")
    (should= :survived (runner/run-spec "/tmp/passing_spec.clj")))

  (it "returns :timeout when spec exceeds timeout"
    (spit "/tmp/hanging_spec.clj"
          "(ns hanging-spec (:require [speclj.core :refer :all]))
           (describe \"hang\" (it \"hangs\" (Thread/sleep 60000) (should= 1 1)))
           (run-specs)")
    (should= :timeout (runner/run-spec "/tmp/hanging_spec.clj" 3000))))

(describe "run-spec-timed"
  (it "returns result and elapsed time"
    (spit "/tmp/timed_spec.clj"
          "(ns timed-spec (:require [speclj.core :refer :all]))
           (describe \"timed\" (it \"passes\" (should= 1 1)))
           (run-specs)")
    (let [{:keys [result elapsed-ms]} (runner/run-spec-timed "/tmp/timed_spec.clj")]
      (should= :survived result)
      (should (pos? elapsed-ms)))))

(describe "source-path->namespace"
  (it "converts simple source path"
    (should= 'empire.combat
             (runner/source-path->namespace "src/empire/combat.cljc")))

  (it "converts underscored path to hyphenated namespace"
    (should= 'empire.map-utils
             (runner/source-path->namespace "src/empire/map_utils.cljc")))

  (it "converts deeply nested path"
    (should= 'empire.computer.ship
             (runner/source-path->namespace "src/empire/computer/ship.cljc"))))

(describe "extract-required-namespaces"
  (it "extracts namespaces from vector require entries"
    (should= #{'baz.quux 'another.ns}
             (runner/extract-required-namespaces
               "(ns foo.bar (:require [baz.quux :as q] [another.ns :refer [x]]))")))

  (it "extracts bare symbol require entries"
    (should= #{'baz.quux}
             (runner/extract-required-namespaces
               "(ns foo.bar (:require baz.quux))")))

  (it "returns empty set when no require"
    (should= #{}
             (runner/extract-required-namespaces
               "(ns foo.bar)")))

  (it "handles mixed vector and bare symbol entries"
    (should= #{'a.b 'c.d}
             (runner/extract-required-namespaces
               "(ns foo (:require [a.b :as ab] c.d))"))))

(describe "find-specs-for-namespace"
  (it "finds spec files that require the given namespace"
    (let [specs (runner/find-specs-for-namespace 'clj-mutate.runner "spec")]
      (should-contain "spec/clj_mutate/runner_spec.clj" specs)))

  (it "returns empty vector when no specs require the namespace"
    (should= [] (runner/find-specs-for-namespace 'nonexistent.namespace "spec")))

  (it "finds multiple specs requiring the same namespace"
    (let [specs (runner/find-specs-for-namespace 'clj-mutate.runner "spec")]
      (should-contain "spec/clj_mutate/runner_spec.clj" specs)
      (should-contain "spec/clj_mutate/core_spec.clj" specs))))

(describe "run-specs"
  (it "returns :survived when all specs pass"
    (spit "/tmp/pass1_spec.clj"
          "(ns pass1-spec (:require [speclj.core :refer :all]))
           (describe \"p1\" (it \"p\" (should= 1 1))) (run-specs)")
    (spit "/tmp/pass2_spec.clj"
          "(ns pass2-spec (:require [speclj.core :refer :all]))
           (describe \"p2\" (it \"p\" (should= 2 2))) (run-specs)")
    (should= :survived
             (runner/run-specs ["/tmp/pass1_spec.clj" "/tmp/pass2_spec.clj"] nil)))

  (it "returns :killed on first failure and short-circuits"
    (spit "/tmp/fail_spec.clj"
          "(ns fail-spec (:require [speclj.core :refer :all]))
           (describe \"f\" (it \"f\" (should= 1 2))) (run-specs)")
    (spit "/tmp/pass3_spec.clj"
          "(ns pass3-spec (:require [speclj.core :refer :all]))
           (describe \"p3\" (it \"p\" (should= 1 1))) (run-specs)")
    (should= :killed
             (runner/run-specs ["/tmp/fail_spec.clj" "/tmp/pass3_spec.clj"] nil))))

(describe "run-specs-timed"
  (it "returns result and elapsed time for multiple specs"
    (spit "/tmp/timed1_spec.clj"
          "(ns timed1-spec (:require [speclj.core :refer :all]))
           (describe \"t1\" (it \"p\" (should= 1 1))) (run-specs)")
    (let [{:keys [result elapsed-ms]} (runner/run-specs-timed ["/tmp/timed1_spec.clj"])]
      (should= :survived result)
      (should (pos? elapsed-ms)))))

(run-specs)
