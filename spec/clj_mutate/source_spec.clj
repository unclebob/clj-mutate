(ns clj-mutate.source-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.source :as source]))

(describe "read-source-forms"
  (it "reads Clojure forms from a string"
    (let [forms (source/read-source-forms "(ns foo) (defn bar [] 42)")]
      (should= 2 (count forms))
      (should= 'ns (first (first forms))))))

(describe "discover-all-mutations"
  (it "finds mutations across multiple forms"
    (let [forms (source/read-source-forms "(defn foo [] (+ 1 2)) (defn bar [] (> x 0))")
          sites (source/discover-all-mutations forms)]
      (should (some #(= (:original %) '+) sites))
      (should (some #(= (:original %) '>) sites))
      (should (some #(= (:original %) 1) sites)))))

(describe "mutate-source-text"
  (it "preserves comments and indentation"
    (let [src "(ns foo)\n;; a comment\n(defn bar [] (+ 1 2))\n"
          forms (source/read-source-forms src)
          sites (source/discover-all-mutations forms)
          plus-site (first (filter #(= (:original %) '+) sites))
          result (source/mutate-source-text src plus-site)]
      (should-contain ";; a comment" result)
      (should-contain "(- 1 2)" result)))

  (it "replaces only the targeted token"
    (let [src "(defn f [] (+ 1 (+ 2 3)))\n"
          forms (source/read-source-forms src)
          sites (source/discover-all-mutations forms)
          first-plus (first (filter #(= (:original %) '+) sites))
          result (source/mutate-source-text src first-plus)]
      (should-contain "(- 1 (+ 2 3))" result)))

  (it "= does not match inside not="
    (let [src "(defn f [] (not= x y))\n"
          forms (source/read-source-forms src)
          sites (source/discover-all-mutations forms)
          eq-sites (filter #(and (= (:original %) '=) (= (:mutant %) 'not=)) sites)]
      (should= 0 (count eq-sites))))

  (it "0 does not match inside 10"
    (let [src "(defn f [] (+ 10 x))\n"
          forms (source/read-source-forms src)
          sites (source/discover-all-mutations forms)
          zero-sites (filter #(and (= (:original %) 0) (= (:mutant %) 1)) sites)]
      (should= 0 (count zero-sites))))

  (it "preserves trailing newline"
    (let [src "(defn f [] (+ 1 2))\n"
          forms (source/read-source-forms src)
          sites (source/discover-all-mutations forms)
          plus-site (first (filter #(= (:original %) '+) sites))
          result (source/mutate-source-text src plus-site)]
      (should (.endsWith result "\n"))))

  (it "fails clearly when a mutation site has no source line"
    (let [src "(def opts {:pretty true})\n"
          site {:original true
                :mutant false
                :line nil
                :column nil
                :description "true -> false"}]
      (should-throw clojure.lang.ExceptionInfo
                    (source/mutate-source-text src site)))))

(describe "partition-by-coverage"
  (it "separates covered from uncovered sites"
    (let [sites [{:line 1 :original '+} {:line 2 :original '-} {:line 3 :original '>}]
          covered-lines #{1 3}
          [covered uncovered] (source/partition-by-coverage sites covered-lines)]
      (should= 2 (count covered))
      (should= 1 (count uncovered))
      (should= 2 (:line (first uncovered)))))

  (it "treats nil-line sites as covered"
    (let [sites [{:line nil :original '+} {:line 5 :original '-}]
          covered-lines #{5}
          [covered uncovered] (source/partition-by-coverage sites covered-lines)]
      (should= 2 (count covered))
      (should= 0 (count uncovered))))

  (it "treats all sites as covered when coverage is nil"
    (let [sites [{:line 1 :original '+} {:line 2 :original '-}]
          [covered uncovered] (source/partition-by-coverage sites nil)]
      (should= 2 (count covered))
      (should= 0 (count uncovered)))))

(describe "integration: discover mutations in a real source file"
  (it "finds mutation sites in mutations.cljc"
    (let [content (slurp "src/clj_mutate/mutations.cljc")
          forms (source/read-source-forms content)
          sites (source/discover-all-mutations forms)]
      (should (> (count sites) 0)))))

(run-specs)
