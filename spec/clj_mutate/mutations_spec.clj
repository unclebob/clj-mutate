(ns clj-mutate.mutations-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.mutations :as m]
            [clj-mutate.core :as core]))

(describe "mutation-rules"
  (it "contains the core mutation set"
    (should (seq m/rules))
    (should (every? #(contains? % :original) m/rules))
    (should (every? #(contains? % :mutant) m/rules))
    (should (every? #(contains? % :category) m/rules))
    (should (every? #(contains? % :position) m/rules))))

(describe "matches-rule?"
  (it "matches a symbol in head position"
    (should (m/matches-rule? {:original '+ :position :head} {:parent '(+ 1 2)} '+)))

  (it "rejects head-position rule when symbol is not first"
    (should-not (m/matches-rule? {:original '+ :position :head} {:parent '(foo + 2)} '+)))

  (it "matches an :any-position rule anywhere"
    (should (m/matches-rule? {:original true :position :any} {:parent '(if true 1)} true))))

(describe "find-mutations"
  (it "finds mutation sites in a simple form"
    (let [sites (m/find-mutations '(+ 1 2))]
      (should (some #(= (:original %) '+) sites))
      (should (some #(= (:original %) 1) sites))))

  (it "finds nested mutation sites"
    (let [sites (m/find-mutations '(if (> x 0) (+ x 1) (- x 1)))]
      (should (>= (count sites) 5))))

  (it "finds mutations inside vectors (let bindings)"
    (let [sites (m/find-mutations '(let [x 0] (+ x 1)))]
      (should (some #(and (= (:original %) 0) (= (:category %) :constant)) sites))))

  (it "returns empty vector for form with no matches"
    (should= [] (m/find-mutations '(foo bar baz)))))

  (it "returns empty vector when walking a literal with no mutation sites"
    (should= [] (m/find-mutations :literal)))

(describe "equivalent mutant suppression"
  (it "suppresses rand-based comparison mutations"
    (doseq [[form original mutant]
            [['(if (< (rand) 0.5) :a :b) '< '<=]
             ['(if (<= (rand) 0.5) :a :b) '<= '<]
             ['(if (> (rand) 0.5) :a :b) '> '>=]]]
      (let [sites (m/find-mutations form)]
        (should-not (some #(and (= (:original %) original) (= (:mutant %) mutant)) sites)))))

  (it "does not suppress non-rand comparison mutations"
    (doseq [[form original mutant]
            [['(if (< x 10) :a :b) '< '<=]
             ['(if (> hits 0) :a :b) '> '>=]]]
      (let [sites (m/find-mutations form)]
        (should (some #(and (= (:original %) original) (= (:mutant %) mutant)) sites))))))

(describe "rand-nth guard suppression"
  (it "suppresses mutations inside the rand-nth single-element guard"
    (doseq [[original mutant]
            [['= 'not=]
             ['if 'if-not]
             [1 0]]]
      (let [sites (m/find-mutations '(if (= 1 (count v)) (first v) (rand-nth v)))]
        (should-not (some #(and (= (:original %) original) (= (:mutant %) mutant)) sites)))))

  (it "does not suppress mutations outside the rand-nth guard"
    (doseq [[form original mutant]
            [['(if (= 1 x) :a :b) '= 'not=]
             ['(if (> x 0) :a :b) 'if 'if-not]]]
      (let [sites (m/find-mutations form)]
        (should (some #(and (= (:original %) original) (= (:mutant %) mutant)) sites))))))

(describe "rand-nth literal pool suppression"
  (it "suppresses 0 inside (rand-nth [0 1])"
    (let [sites (m/find-mutations '(rand-nth [0 1]))]
      (should-not (some #(and (= (:original %) 0) (= (:mutant %) 1)) sites))))

  (it "suppresses 1 inside (rand-nth [0 1])"
    (let [sites (m/find-mutations '(rand-nth [0 1]))]
      (should-not (some #(and (= (:original %) 1) (= (:mutant %) 0)) sites))))

  (it "suppresses 0 inside nested (rand-nth [[-1 0] [1 0]])"
    (let [sites (m/find-mutations '(rand-nth [[-1 0] [1 0]]))]
      (should-not (some #(and (= (:original %) 0) (= (:mutant %) 1)) sites))))

  (it "does not suppress 0 in normal code"
    (let [sites (m/find-mutations '(+ x 0))]
      (should (some #(and (= (:original %) 0) (= (:mutant %) 1)) sites))))

  (it "does not suppress 0 in let binding vectors"
    (let [sites (m/find-mutations '(let [x 0] (+ x 1)))]
      (should (some #(and (= (:original %) 0) (= (:mutant %) 1)) sites)))))

(describe "subvec trim boundary suppression"
  (it "suppresses > -> >= inside (if (> (count v) 10) (subvec ...))"
    (let [sites (m/find-mutations '(if (> (count v) 10) (subvec v 0 10) v))]
      (should-not (some #(and (= (:original %) '>) (= (:mutant %) '>=)) sites))))

  (it "does not suppress > -> >= in non-subvec contexts"
    (let [sites (m/find-mutations '(if (> x 10) :a :b))]
      (should (some #(and (= (:original %) '>) (= (:mutant %) '>=)) sites)))))

(describe "line numbers"
  (it "attaches :line from reader metadata for symbols"
    (let [forms (core/read-source-forms "(defn foo [] (+ 1 2))")
          sites (m/find-mutations (first forms))
          plus-site (first (filter #(= (:original %) '+) sites))]
      (should-not-be-nil (:line plus-site))))

  (it "attaches :line from parent metadata for literals"
    (let [forms (core/read-source-forms "(defn foo [] (+ 1 2))")
          sites (m/find-mutations (first forms))
          one-site (first (filter #(= (:original %) 1) sites))]
      (should-not-be-nil (:line one-site))))

  (it "returns nil :line for forms without metadata"
    (let [form (list (symbol "+") 1 2)
          sites (m/find-mutations form)
          plus-site (first (filter #(= (:original %) '+) sites))]
      (should-be-nil (:line plus-site)))))

(describe "apply-mutation"
  (it "applies mutation at a specific index"
    (let [form '(+ 1 2)
          sites (m/find-mutations form)
          plus-site (first (filter #(= (:original %) '+) sites))
          result (m/apply-mutation form (:index plus-site))]
      (should= '(- 1 2) result)))

  (it "leaves other sites unchanged"
    (let [form '(+ 1 2)
          sites (m/find-mutations form)
          one-site (first (filter #(= (:original %) 1) sites))
          result (m/apply-mutation form (:index one-site))]
      (should= '(+ 0 2) result)))

  (it "returns the original form when target index is missing"
    (should= '(foo bar baz) (m/apply-mutation '(foo bar baz) 0))))

(describe "rebuild-coll"
  (it "rebuilds seqs by walking each child"
    (let [walk (fn [_ _ node] (if (number? node) (inc node) node))]
      (should= '(+ 2 3) (#'m/rebuild-coll walk nil nil '(+ 1 2)))))

  (it "rebuilds vectors by walking each child"
    (let [walk (fn [_ _ node] (if (number? node) (inc node) node))]
      (should= [2 3] (#'m/rebuild-coll walk nil nil [1 2]))))

  (it "rebuilds maps by walking keys and values"
    (let [walk (fn [_ _ node]
                 (cond
                   (= node :a) :b
                   (= node 1) 2
                   :else node))]
      (should= {:b 2} (#'m/rebuild-coll walk nil nil {:a 1}))))

  (it "rebuilds sets by walking each member"
    (let [walk (fn [_ _ node] (if (number? node) (inc node) node))]
      (should= #{2 3} (#'m/rebuild-coll walk nil nil #{1 2}))))

  (it "returns non-collections unchanged"
    (let [walk (fn [_ _ node] (if (number? node) (inc node) node))]
      (should= 42 (#'m/rebuild-coll walk nil nil 42)))))

(run-specs)
