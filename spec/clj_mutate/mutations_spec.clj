(ns clj-mutate.mutations-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.mutations :as m]
            [clj-mutate.source :as source]))

(defn site-pairs [sites]
  (set (map (juxt :original :mutant) sites)))

(defn has-site? [original mutant sites]
  (contains? (site-pairs sites) [original mutant]))

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

  (it "matches a head symbol whose zipper parent is an expanded #() fn*"
    (should (m/matches-rule? {:original '= :position :head}
                             {:parent '(fn* [p1] (= item p1))}
                             '=)))

  (it "does not treat non-head children of #() as operators"
    (should-not (m/matches-rule? {:original '= :position :head}
                                 {:parent '(fn* [p1] (= item p1))}
                                 'item)))

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
    (should= [] (m/find-mutations '(foo bar baz))))

  (it "returns empty vector when walking a literal with no mutation sites"
    (should= [] (m/find-mutations :literal))))

(describe "equivalent mutant suppression"
  (it "suppresses rand-based comparison mutations"
    (let [lt-sites (m/find-mutations '(if (< (rand) 0.5) :a :b))
          lte-sites (m/find-mutations '(if (<= (rand) 0.5) :a :b))
          gt-sites (m/find-mutations '(if (> (rand) 0.5) :a :b))]
      (should= #{['if 'if-not]} (site-pairs lt-sites))
      (should-not (has-site? '< '<= lt-sites))
      (should= #{['if 'if-not]} (site-pairs lte-sites))
      (should-not (has-site? '<= '< lte-sites))
      (should= #{['if 'if-not]} (site-pairs gt-sites))
      (should-not (has-site? '> '>= gt-sites))))

  (it "does not suppress non-rand comparison mutations"
    (let [lt-sites (m/find-mutations '(if (< x 10) :a :b))
          gt-sites (m/find-mutations '(if (> hits 0) :a :b))]
      (should (has-site? '< '<= lt-sites))
      (should (has-site? '> '>= gt-sites)))))

(describe "rand-nth guard suppression"
  (it "suppresses a valid if guard"
    (let [sites (m/find-mutations '(if (= 1 (count v)) (first v) (rand-nth v)))]
      (should-not (has-site? '= 'not= sites))
      (should-not (has-site? 'if 'if-not sites))
      (should-not (has-site? 1 0 sites))))

  (it "suppresses a valid branch-reversed if-not guard"
    (let [sites (m/find-mutations '(if-not (= 1 (count v)) (rand-nth v) (first v)))]
      (should-not (has-site? '= 'not= sites))
      (should-not (has-site? 'if-not 'if sites))
      (should-not (has-site? 1 0 sites))))

  (it "does not suppress an if-not guard whose branches were not reversed"
    (let [sites (m/find-mutations '(if-not (= 1 (count v)) (first v) (rand-nth v)))]
      (should (has-site? '= 'not= sites))
      (should (has-site? 'if-not 'if sites))
      (should (has-site? 1 0 sites))))

  (it "does not suppress a lookalike headed by another form"
    (let [sites (m/find-mutations '(choose (= 1 (count v)) (first v) (rand-nth v)))]
      (should (has-site? '= 'not= sites))
      (should (has-site? 1 0 sites))))

  (it "does not suppress mutations outside the rand-nth guard"
    (let [equality-sites (m/find-mutations '(if (= 1 x) :a :b))
          conditional-sites (m/find-mutations '(if (> x 0) :a :b))]
      (should (has-site? '= 'not= equality-sites))
      (should (has-site? 'if 'if-not conditional-sites)))))

(describe "rand-nth literal pool suppression"
  (it "suppresses literal-pool mutations inside rand-nth"
    (let [flat-suppressed-sites (m/find-mutations '(rand-nth [0 1]))
          flat-control-sites (m/find-mutations [0 1])
          nested-suppressed-sites (m/find-mutations '(rand-nth [[-1 0] [1 0]]))
          nested-control-sites (m/find-mutations '(vector [-1 0] [1 0]))]
      (should (has-site? 0 1 flat-control-sites))
      (should-not (has-site? 0 1 flat-suppressed-sites))
      (should (has-site? 1 0 flat-control-sites))
      (should-not (has-site? 1 0 flat-suppressed-sites))
      (should (has-site? 0 1 nested-control-sites))
      (should-not (has-site? 0 1 nested-suppressed-sites))))

  (it "does not suppress literal-pool mutations outside rand-nth"
    (let [addition-sites (m/find-mutations '(+ x 0))
          let-sites (m/find-mutations '(let [x 0] (+ x 1)))
          nested-vector-sites (m/find-mutations '[[-1 0] [1 0]])
          bound-vector-sites (m/find-mutations '(let [dirs [[-1 0] [1 0]]] dirs))]
      (should (has-site? 0 1 addition-sites))
      (should (has-site? 0 1 let-sites))
      (should (has-site? 0 1 nested-vector-sites))
      (should (has-site? 0 1 bound-vector-sites)))))

(describe "subvec trim boundary suppression"
  (it "suppresses > -> >= inside (if (> (count v) 10) (subvec ...))"
    (let [sites (m/find-mutations '(if (> (count v) 10) (subvec v 0 10) v))]
      (should (has-site? 'if 'if-not sites))
      (should-not (has-site? '> '>= sites))))

  (it "suppresses >= -> > at the same if trim boundary"
    (let [sites (m/find-mutations '(if (>= (count v) 10) (subvec v 0 10) v))]
      (should-not (has-site? '>= '> sites))))

  (it "suppresses > -> >= in a branch-reversed if-not trim"
    (let [sites (m/find-mutations '(if-not (> (count v) 10) v (subvec v 0 10)))]
      (should-not (has-site? '> '>= sites))))

  (it "suppresses >= -> > in a branch-reversed if-not trim"
    (let [sites (m/find-mutations '(if-not (>= (count v) 10) v (subvec v 0 10)))]
      (should-not (has-site? '>= '> sites))))

  (it "does not suppress an if-not trim whose branches were not reversed"
    (let [sites (m/find-mutations '(if-not (> (count v) 10) (subvec v 0 10) v))]
      (should (has-site? '> '>= sites))))

  (it "does not suppress a >= if-not trim whose branches were not reversed"
    (let [sites (m/find-mutations '(if-not (>= (count v) 10) (subvec v 0 10) v))]
      (should (has-site? '>= '> sites))))

  (it "does not suppress a > if trim whose branches were reversed"
    (let [sites (m/find-mutations '(if (> (count v) 10) v (subvec v 0 10)))]
      (should (has-site? '> '>= sites))))

  (it "does not suppress a >= if trim whose branches were reversed"
    (let [sites (m/find-mutations '(if (>= (count v) 10) v (subvec v 0 10)))]
      (should (has-site? '>= '> sites))))

  (it "does not suppress a trim lookalike headed by another form"
    (let [sites (m/find-mutations '(choose (> (count v) 10) (subvec v 0 10) v))]
      (should (has-site? '> '>= sites))))

  (it "does not suppress > -> >= in non-subvec contexts"
    (let [sites (m/find-mutations '(if (> x 10) :a :b))]
      (should (has-site? '> '>= sites))))

  (it "does not suppress > when the then-branch is subvec but the test is not count"
    (let [sites (m/find-mutations '(if (> x 10) (subvec v 0 x) v))]
      (should (has-site? '> '>= sites)))))

(describe "line numbers"
  (it "attaches :line from reader metadata for symbols"
    (let [forms (source/read-source-forms "(defn foo [] (+ 1 2))")
          sites (m/find-mutations (first forms))
          plus-site (first (filter #(= (:original %) '+) sites))]
      (should-not-be-nil (:line plus-site))))

  (it "attaches :line from parent metadata for literals"
    (let [forms (source/read-source-forms "(defn foo [] (+ 1 2))")
          sites (m/find-mutations (first forms))
          one-site (first (filter #(= (:original %) 1) sites))]
      (should-not-be-nil (:line one-site))))

  (it "inherits the nearest ancestor source location when parent metadata is absent"
    (let [form (with-meta (list 'def 'opts {:pretty true})
                 {:line 42 :column 1})
          sites (m/find-mutations form)
          true-site (first (filter #(= (:original %) true) sites))]
      (should= 42 (:line true-site))
      (should= 1 (:column true-site))))

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
  (it "rebuilds collections by walking each child"
    (let [increment-numbers (fn [_ _ _ node] (if (number? node) (inc node) node))]
      (doseq [[input expected]
              [['(+ 1 2) '(+ 2 3)]
               [[1 2] [2 3]]
               [#{1 2} #{2 3}]]]
        (should= expected (#'m/rebuild-coll increment-numbers nil nil nil input)))))

  (it "rebuilds maps by walking keys and values"
    (let [walk (fn [_ _ _ node]
                 (cond
                   (= node :a) :b
                   (= node 1) 2
                   :else node))]
      (should= {:b 2} (#'m/rebuild-coll walk nil nil nil {:a 1}))))

  (it "returns non-collections unchanged"
    (let [walk (fn [_ _ _ node] (if (number? node) (inc node) node))]
      (should= 42 (#'m/rebuild-coll walk nil nil nil 42)))))

(run-specs)
