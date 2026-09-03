(ns clj-mutate.mutations)

(def rules-version "3")

(defn- rand-comparison?
  "True if parent is a comparison form with (rand) as second element.
   e.g. (< (rand) 0.5) — mutating < to <= is equivalent on doubles."
  [{:keys [parent]}]
  (and (seq? parent)
       (>= (count parent) 3)
       (let [second-elem (second parent)]
         (and (seq? second-elem)
              (= 'rand (first second-elem))))))

(defn- unary-call?
  [operator argument form]
  (and (seq? form)
       (= operator (first form))
       (= argument (second form))
       (= 2 (count form))))

(defn- single-element-target
  [condition]
  (when (and (seq? condition)
             (= '= (first condition))
             (= 3 (count condition)))
    (let [operands (rest condition)
          count-form (first (filter #(and (seq? %)
                                          (= 'count (first %))
                                          (= 2 (count %)))
                                    operands))]
      (when (and count-form (some #(= 1 %) operands))
        (second count-form)))))

(defn- rand-nth-guard-form?
  "True for a single-element rand-nth guard. The if-not spelling must reverse
   the two branches to preserve the same behavior as the if spelling."
  [form]
  (when (and (seq? form) (= 4 (count form)))
    (let [[head condition then-form else-form] form
          target (single-element-target condition)
          first-form? #(unary-call? 'first target %)
          rand-form? #(unary-call? 'rand-nth target %)]
      (and target
           (case head
             if (and (first-form? then-form) (rand-form? else-form))
             if-not (and (rand-form? then-form) (first-form? else-form))
             nil)))))

(defn- rand-nth-single-element-guard?
  "True inside (if (= 1 (count _)) (first _) (rand-nth _)).
   Checks parent (for head symbols like if) and grandparent (for nested)."
  [{:keys [parent grandparent]}]
  (or (rand-nth-guard-form? parent)
      (rand-nth-guard-form? grandparent)))

(defn- rand-nth-form?
  [form]
  (and (seq? form) (= 'rand-nth (first form))))

(defn- inside-rand-nth-literal?
  "True for constants inside (rand-nth [...]). Pool values are equivalent.
   Handles both flat (rand-nth [0 1]) and nested (rand-nth [[-1 0] [1 0]])."
  [{:keys [parent grandparent great-grandparent]}]
  (and (vector? parent)
       (or (rand-nth-form? grandparent)
           (and (vector? grandparent)
                (every? #(and (vector? %) (every? number? %)) grandparent)
                (rand-nth-form? great-grandparent)))))

(defn- count-form?
  [form]
  (and (seq? form) (= 'count (first form))))

(defn- subvec-trim-boundary?
  "True when toggling >/>= only changes which branch returns the same vector
   at the trim boundary. if-not must reverse the ordinary if branches."
  [{:keys [parent grandparent]}]
  (and (seq? parent)
       (or (= '> (first parent)) (= '>= (first parent)))
       (= 3 (count parent))
       (seq? grandparent)
       (= 4 (count grandparent))
       (let [[operator left right] parent
             [count-expr boundary] (if (count-form? left)
                                     [left right]
                                     [right left])
             target (when (count-form? count-expr) (second count-expr))
             [_ _ then-form else-form] grandparent
             trim-form? (fn [form]
                          (= (list 'subvec target 0 boundary) form))]
         (and (#{'> '>=} operator)
              target
              (case (first grandparent)
                if (and (trim-form? then-form) (= target else-form))
                if-not (and (= target then-form) (trim-form? else-form))
                nil)))))

(def rules
  [{:id :arithmetic/add-to-subtract :original '+ :mutant '- :category :arithmetic :position :head}
   {:id :arithmetic/subtract-to-add :original '- :mutant '+ :category :arithmetic :position :head}
   {:id :arithmetic/multiply-to-divide :original '* :mutant '/ :category :arithmetic :position :head}
   {:id :arithmetic/increment-to-decrement :original 'inc :mutant 'dec :category :arithmetic :position :head}
   {:id :arithmetic/decrement-to-increment :original 'dec :mutant 'inc :category :arithmetic :position :head}
   {:id :comparison/greater-to-greater-equal :original '> :mutant '>= :category :comparison :position :head :suppress-when [rand-comparison? subvec-trim-boundary?]}
   {:id :comparison/greater-equal-to-greater :original '>= :mutant '> :category :comparison :position :head :suppress-when [rand-comparison? subvec-trim-boundary?]}
   {:id :comparison/less-to-less-equal :original '< :mutant '<= :category :comparison :position :head :suppress-when [rand-comparison?]}
   {:id :comparison/less-equal-to-less :original '<= :mutant '< :category :comparison :position :head :suppress-when [rand-comparison?]}
   {:id :equality/equal-to-not-equal :original '= :mutant 'not= :category :equality :position :head :suppress-when [rand-nth-single-element-guard?]}
   {:id :equality/not-equal-to-equal :original 'not= :mutant '= :category :equality :position :head}
   {:id :boolean/true-to-false :original true :mutant false :category :boolean :position :any}
   {:id :boolean/false-to-true :original false :mutant true :category :boolean :position :any}
   {:id :conditional/if-to-if-not :original 'if :mutant 'if-not :category :conditional :position :head :suppress-when [rand-nth-single-element-guard?]}
   {:id :conditional/if-not-to-if :original 'if-not :mutant 'if :category :conditional :position :head :suppress-when [rand-nth-single-element-guard?]}
   {:id :conditional/when-to-when-not :original 'when :mutant 'when-not :category :conditional :position :head}
   {:id :conditional/when-not-to-when :original 'when-not :mutant 'when :category :conditional :position :head}
   {:id :constant/zero-to-one :original 0 :mutant 1 :category :constant :position :any :suppress-when [rand-nth-single-element-guard? inside-rand-nth-literal?]}
   {:id :constant/one-to-zero :original 1 :mutant 0 :category :constant :position :any :suppress-when [rand-nth-single-element-guard? inside-rand-nth-literal?]}])

(defn matches-rule?
  "True if rule matches node. For :head rules, node must be
   a list/seq and the symbol must be its first element.
   Suppressed if any :suppress-when predicate returns true for context."
  [rule context node]
  (let [parent (:parent context)]
    (and (= (:original rule) node)
         (not (when-let [suppressors (:suppress-when rule)]
                (some #(% context) suppressors)))
         (or (= :any (:position rule))
             (and (= :head (:position rule))
                  (seq? parent)
                  (= node (first parent)))))))

(defn matching-rule
  "Return the first mutation rule matching node in context."
  [context node]
  (first (filter #(matches-rule? % context node) rules)))

(defn- source-location
  [x]
  (when-let [m (meta x)]
    (when-let [line (:line m)]
      {:line line
       :column (:column m)})))

(defn- walk-children
  "Recurse into child nodes of any collection type."
  [walk-fn grandparent parent node nearest-location]
  (cond
    (seq? node) (doseq [child node] (walk-fn grandparent parent node child nearest-location))
    (vector? node) (doseq [child node] (walk-fn grandparent parent node child nearest-location))
    (map? node) (doseq [[k v] node]
                  (walk-fn grandparent parent node k nearest-location)
                  (walk-fn grandparent parent node v nearest-location))
    (set? node) (doseq [child node] (walk-fn grandparent parent node child nearest-location))))

(defn find-mutations
  "Walk form tree, return vector of mutation sites.
   Each site: {:index N :original form :mutant form :description \"...\"}.
   SYNC WARNING: find-mutations and apply-mutation must walk the tree
   identically so mutation indices match. Any change to traversal order,
   grandparent tracking, or suppression logic must be mirrored in both."
  [form]
  (let [counter (atom 0)
        sites (atom [])]
    (letfn [(walk [great-grandparent grandparent parent node nearest-location]
              (let [site-location (or (source-location node)
                                      (source-location parent)
                                      nearest-location)
                    context {:parent parent
                             :grandparent grandparent
                             :great-grandparent great-grandparent}]
                (when-let [rule (matching-rule context node)]
                  (swap! sites conj {:index @counter
                                     :rule-id (:id rule)
                                     :original (:original rule)
                                     :mutant (:mutant rule)
                                     :category (:category rule)
                                     :line (:line site-location)
                                     :column (:column site-location)
                                     :description (str (:original rule) " -> " (:mutant rule))})
                  (swap! counter inc))
                (walk-children walk grandparent parent node site-location)))]
      (walk nil nil nil form nil))
    @sites))

(defn- rebuild-coll
  "Rebuild a collection after walking its children."
  [walk-fn great-grandparent grandparent parent node]
  (cond
    (seq? node) (apply list (map #(walk-fn grandparent parent node %) node))
    (vector? node) (mapv #(walk-fn grandparent parent node %) node)
    (map? node) (into {} (map (fn [[k v]] [(walk-fn grandparent parent node k) (walk-fn grandparent parent node v)]) node))
    (set? node) (into #{} (map #(walk-fn grandparent parent node %) node))
    :else node))

(defn apply-mutation
  "Walk form tree, apply the mutation at the given index.
   Returns the mutated form.
   SYNC WARNING: find-mutations and apply-mutation must walk the tree
   identically so mutation indices match. Any change to traversal order,
   grandparent tracking, or suppression logic must be mirrored in both."
  [form target-index]
  (let [counter (atom 0)]
    (letfn [(walk [great-grandparent grandparent parent node]
              (let [context {:parent parent
                             :grandparent grandparent
                             :great-grandparent great-grandparent}]
                (if-let [rule (matching-rule context node)]
                  (let [idx @counter]
                    (swap! counter inc)
                    (if (= idx target-index)
                      (if (seq? node)
                        (let [mutant (:mutant rule)
                              new-parent (cons mutant (rest node))]
                          (apply list mutant (map #(walk grandparent parent new-parent %) (rest node))))
                        (:mutant rule))
                      (rebuild-coll walk great-grandparent grandparent parent node)))
                  (rebuild-coll walk great-grandparent grandparent parent node))))]
      (walk nil nil nil form))))

;; clj-mutate-manifest-begin
;; {:version 2, :hash-algorithm :sha256-source-v1, :verified? true, :tested-at "2026-09-03T13:12:49.84391-05:00", :module-hash "af96e94c5a536d8ad84d06fbb067458ae3fcc4fa12ca3524a80ed130f68afacc", :provenance {:mutation-rules-version "3", :test-command "clj -M:spec --tag ~no-mutate", :test-roots ["spec" "spec-jvm"], :test-profile-fingerprint "42c756547b386d80427e8cfe970b6aec1187ecab65e38439efe0a62940a3d419"}, :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line 1, :hash "aa669a65b8619d2ad427b3200d03af566f3eeaf352154556b14943d4ad572fcd"} {:id "def/rules-version", :kind "def", :line 3, :end-line 3, :hash "06ae39aa6f4c48b91afdd630f142e3ba38c1924425879d909a942450a7c733ca"} {:id "defn-/rand-comparison?", :kind "defn-", :line 5, :end-line 13, :hash "b31827a2865e6d1e597e8e8eeeb03b73f8f7cf869bf6893257953ec9b3b54077"} {:id "defn-/unary-call?", :kind "defn-", :line 15, :end-line 20, :hash "0bbea070d447d58c3c220c9b25e43f393c125ef20d4ac04c4cb5846bbff42476"} {:id "defn-/single-element-target", :kind "defn-", :line 22, :end-line 33, :hash "a3f0aac9426cfb4dd015a7320a22248c524246b45ecd985e773fed7021e7ce4d"} {:id "defn-/rand-nth-guard-form?", :kind "defn-", :line 35, :end-line 48, :hash "a7c30052d058a94fc0636096798955e3b533964b471a00f391ba2106d878720b"} {:id "defn-/rand-nth-single-element-guard?", :kind "defn-", :line 50, :end-line 55, :hash "bdd7f6b31b1c772d83b49064ca433ddb54c0ca4458e2bac0c8e82fd4dcab11e0"} {:id "defn-/rand-nth-form?", :kind "defn-", :line 57, :end-line 59, :hash "d30c11fa81e0f4d46eaa8cd1fba91cc82c8dba4b57785b97a9ddaca5b52a639d"} {:id "defn-/inside-rand-nth-literal?", :kind "defn-", :line 61, :end-line 69, :hash "ae64d0b871020acea9caaa83c5e68195b2cfad67ac4a7866b38d388db9f40ca2"} {:id "defn-/count-form?", :kind "defn-", :line 71, :end-line 73, :hash "9807bc2900a7b41afbb3a5362d15c06eefd47e0b2b1dc30b5aaca1f9c6fdf55e"} {:id "defn-/subvec-trim-boundary?", :kind "defn-", :line 75, :end-line 97, :hash "fcf54abbc7d7dd97dd6bbbb95597f2846ebfb10925f6cedcb48cc1b976772af8"} {:id "def/rules", :kind "def", :line 99, :end-line 118, :hash "0cb3b24be0738ca1a57bf17dd39d57a2de27dc66d99e97cf20d72d1be057ee87"} {:id "defn/matches-rule?", :kind "defn", :line 120, :end-line 132, :hash "e2f51d04a3fe62c47b74b98ab1b6471af3f016169c4fbb64255126dad82cdbbe"} {:id "defn/matching-rule", :kind "defn", :line 134, :end-line 137, :hash "6fd9aacbe457450ba63a501beda38d688bbb72d51db55d3d1df4a8f60ffcdf82"} {:id "defn-/source-location", :kind "defn-", :line 139, :end-line 144, :hash "7750e3199488f517610453f62949896e0e8429a1471fe4d391f462dfdd00291f"} {:id "defn-/walk-children", :kind "defn-", :line 146, :end-line 155, :hash "8fd239ea9142e263634c3e1c33d2e10ec37d37a8e23a5408d95f61e41451405d"} {:id "defn/find-mutations", :kind "defn", :line 157, :end-line 185, :hash "ece615c195dfbe49e7a02cfa2ddf9fb9d591fae03c4f45aee1d6c64b68215468"} {:id "defn-/rebuild-coll", :kind "defn-", :line 187, :end-line 195, :hash "60107320f9c6f5c43034926a38f5313cf53b2e3073547119f596a5c70131febd"} {:id "defn/apply-mutation", :kind "defn", :line 197, :end-line 220, :hash "40b9bb4a5cb55b88426ae44000aa1f58f0b565a902af72491f5b5fa259bf2823"}]}
;; clj-mutate-manifest-end
