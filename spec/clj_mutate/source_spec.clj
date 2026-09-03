(ns clj-mutate.source-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.mutations :as mutations]
            [clj-mutate.source :as source]
            [clj-mutate.syntax :as syntax]))

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
          sites (source/discover-mutations src)
          plus-site (first (filter #(= (:original %) '+) sites))
          result (source/mutate-source-text src plus-site)]
      (should-contain ";; a comment" result)
      (should-contain "(- 1 2)" result)))

  (it "replaces only the targeted token"
    (let [src "(defn f [] (+ 1 (+ 2 3)))\n"
          sites (source/discover-mutations src)
          first-plus (first (filter #(= (:original %) '+) sites))
          result (source/mutate-source-text src first-plus)]
      (should-contain "(- 1 (+ 2 3))" result)))

  (it "= does not match inside not="
    (let [src "(defn f [] (not= x y))\n"
          sites (source/discover-mutations src)
          eq-sites (filter #(and (= (:original %) '=) (= (:mutant %) 'not=)) sites)]
      (should= 0 (count eq-sites))))

  (it "discovers head mutations inside #() reader macros"
    (let [src "(defn f [item] #(= item %))\n"
          sites (source/discover-mutations src)
          eq-site (first (filter #(= '= (:original %)) sites))]
      (should-not-be-nil eq-site)
      (should= 'not= (:mutant eq-site))
      (should-contain "#(not= item %)" (source/mutate-source-text src eq-site))))

  (it "discovers arithmetic and constant mutations inside #()"
    (let [src "(defn f [x] #(+ % 1))\n"
          sites (source/discover-mutations src)
          pairs (set (map (juxt :original :mutant) sites))]
      (should (contains? pairs ['+ '-]))
      (should (contains? pairs [1 0]))))

  (it "0 does not match inside 10"
    (let [src "(defn f [] (+ 10 x))\n"
          sites (source/discover-mutations src)
          zero-sites (filter #(and (= (:original %) 0) (= (:mutant %) 1)) sites)]
      (should= 0 (count zero-sites))))

  (it "preserves trailing newline"
    (let [src "(defn f [] (+ 1 2))\n"
          sites (source/discover-mutations src)
          plus-site (first (filter #(= (:original %) '+) sites))
          result (source/mutate-source-text src plus-site)]
      (should (.endsWith result "\n"))))

  (it "targets literals in a multiline map at their exact positions"
    (let [src "(def opts {:first false\n           ;; keep this comment\n           :second false})\n"
          sites (filter #(= false (:original %)) (source/discover-mutations src))]
      (should= [[1 19] [3 20]] (mapv (juxt :line :column) sites))
      (should-contain ":first true" (source/mutate-source-text src (first sites)))
      (should-contain ":second false" (source/mutate-source-text src (first sites)))
      (should-contain ";; keep this comment" (source/mutate-source-text src (first sites)))))

  (it "distinguishes repeated identical literals on one line"
    (let [src "(def opts {:a false :b false})\n"
          sites (vec (filter #(= false (:original %)) (source/discover-mutations src)))
          outputs (mapv #(source/mutate-source-text src %) sites)]
      (should= 2 (count sites))
      (should= 2 (count (set (map :mutation-id sites))))
      (should= 2 (count (set outputs)))
      (should-not= (:node-path (first sites)) (:node-path (second sites)))))

  (it "keeps persistent mutation identities unique across repeated named forms"
    (let [src "(defn f [] (+ 1 2))\n(defn f [] (+ 1 2))\n"
          sites (source/discover-mutations src)]
      (should= (count sites) (count (set (map :mutation-id sites))))
      (should= ["defn/f" "defn/f#2"]
               (->> sites (map :form-id) distinct vec))))

  (it "rejects a site whose exact node no longer matches"
    (let [src "(def opts {:pretty true})\n"
          site (first (filter #(= true (:original %)) (source/discover-mutations src)))]
      (try
        (source/mutate-source-text "(def opts {:pretty false})\n" site)
        (should false)
        (catch clojure.lang.ExceptionInfo ex
          (should= :mutation-target-mismatch (:reason (ex-data ex)))))))

  (it "rejects a renderer that produces unchanged source"
    (let [src "(defn f [] (+ 1 2))\n"
          site (first (source/discover-mutations src))]
      (with-redefs [syntax/replace-source (fn [_ _] src)]
        (try
          (source/mutate-source-text src site)
          (should false)
          (catch clojure.lang.ExceptionInfo ex
            (should= :no-op-mutation (:reason (ex-data ex))))))))

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

  (it "treats nil-line sites as uncovered"
    (let [sites [{:line nil :original '+} {:line 5 :original '-}]
          covered-lines #{5}
          [covered uncovered] (source/partition-by-coverage sites covered-lines)]
      (should= 1 (count covered))
      (should= 1 (count uncovered))
      (should= nil (:line (first uncovered)))))

  (it "treats all sites as covered when coverage is nil"
    (let [sites [{:line 1 :original '+} {:line 2 :original '-}]
          [covered uncovered] (source/partition-by-coverage sites nil)]
      (should= 2 (count covered))
      (should= 0 (count uncovered))))

  (it "treats nil-line sites as uncovered even when coverage is nil"
    (let [sites [{:line nil :original '+} {:line 2 :original '-}]
          [covered uncovered] (source/partition-by-coverage sites nil)]
      (should= 1 (count covered))
      (should= 1 (count uncovered)))))

(describe "text replacement vs tree replacement"
  (it "replaces the same original token that apply-mutation would"
    (let [src "(defn f [] (+ 1 2))\n"
          forms (source/read-source-forms src)
          sites (source/discover-mutations src)]
      (should (seq sites))
      (doseq [site sites]
        (let [form (nth forms (:form-index site))
              mutated-form (mutations/apply-mutation form (:index site))
              mutated-text (source/mutate-source-text src site)]
          (should-not= form mutated-form)
          (should-contain (str (:mutant site)) mutated-text)
          (should-contain (str (:original site)) src))))))

(describe "integration: discover mutations in a real source file"
  (it "finds mutation sites in mutations.cljc"
    (let [content (slurp "src/clj_mutate/mutations.cljc")
          sites (source/discover-mutations content)]
      (should (> (count sites) 0)))))

(run-specs)
