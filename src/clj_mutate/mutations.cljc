(ns clj-mutate.mutations)

(defn- rand-comparison?
  "True if parent is a comparison form with (rand) as second element.
   e.g. (< (rand) 0.5) — mutating < to <= is equivalent on doubles."
  [{:keys [parent]}]
  (and (seq? parent)
       (>= (count parent) 3)
       (let [second-elem (second parent)]
         (and (seq? second-elem)
              (= 'rand (first second-elem))))))

(defn- rand-nth-guard-form?
  "True if form matches (if (= 1 (count _)) (first _) (rand-nth _))."
  [form]
  (and (seq? form)
       (let [head (first form)]
         (and (or (= 'if head) (= 'if-not head))
              (>= (count form) 4)
              (let [cond-form (second form)
                    else-form (nth form 3 nil)]
                (and (seq? cond-form)
                     (= '= (first cond-form))
                     (some #(and (number? %) (= 1 %)) (rest cond-form))
                     (seq? else-form)
                     (= 'rand-nth (first else-form))))))))

(defn- rand-nth-single-element-guard?
  "True inside (if (= 1 (count _)) (first _) (rand-nth _)).
   Checks parent (for head symbols like if) and grandparent (for nested)."
  [{:keys [parent grandparent]}]
  (or (rand-nth-guard-form? parent)
      (rand-nth-guard-form? grandparent)))

(defn- inside-rand-nth-literal?
  "True for constants inside (rand-nth [...]). Pool values are equivalent.
   Handles both flat (rand-nth [0 1]) and nested (rand-nth [[-1 0] [1 0]])."
  [{:keys [parent grandparent]}]
  (and (vector? parent)
       (or (and (seq? grandparent) (= 'rand-nth (first grandparent)))
           (and (vector? grandparent)
                (every? #(and (vector? %) (every? number? %)) grandparent)))))

(defn- subvec-trim-boundary?
  "True for > -> >= inside (if (> (count v) N) (subvec ...) v).
   Off-by-one at subvec boundary is equivalent."
  [{:keys [grandparent]}]
  (and (seq? grandparent)
       (let [head (first grandparent)]
         (and (or (= 'if head) (= 'if-not head))
              (>= (count grandparent) 4)
              (let [then-form (nth grandparent 2 nil)]
                (and (seq? then-form)
                     (= 'subvec (first then-form))))))))

(def rules
  [{:original '+   :mutant '-   :category :arithmetic :position :head}
   {:original '-   :mutant '+   :category :arithmetic :position :head}
   {:original '*   :mutant '/   :category :arithmetic :position :head}
   {:original 'inc :mutant 'dec :category :arithmetic :position :head}
   {:original 'dec :mutant 'inc :category :arithmetic :position :head}
   {:original '>   :mutant '>=  :category :comparison :position :head :suppress-when [rand-comparison? subvec-trim-boundary?]}
   {:original '>=  :mutant '>   :category :comparison :position :head :suppress-when [rand-comparison?]}
   {:original '<   :mutant '<=  :category :comparison :position :head :suppress-when [rand-comparison?]}
   {:original '<=  :mutant '<   :category :comparison :position :head :suppress-when [rand-comparison?]}
   {:original '=   :mutant 'not= :category :equality :position :head :suppress-when [rand-nth-single-element-guard?]}
   {:original 'not= :mutant '= :category :equality :position :head}
   {:original true  :mutant false :category :boolean :position :any}
   {:original false :mutant true  :category :boolean :position :any}
   {:original 'if      :mutant 'if-not   :category :conditional :position :head :suppress-when [rand-nth-single-element-guard?]}
   {:original 'if-not  :mutant 'if       :category :conditional :position :head :suppress-when [rand-nth-single-element-guard?]}
   {:original 'when    :mutant 'when-not :category :conditional :position :head}
   {:original 'when-not :mutant 'when    :category :conditional :position :head}
   {:original 0 :mutant 1 :category :constant :position :any :suppress-when [rand-nth-single-element-guard? inside-rand-nth-literal?]}
   {:original 1 :mutant 0 :category :constant :position :any :suppress-when [rand-nth-single-element-guard? inside-rand-nth-literal?]}])

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

(defn- first-matching-rule [context node]
  (first (filter #(matches-rule? % context node) rules)))

(defn- node-line
  "Extract line number for a mutation site.
   Symbols get reader metadata; literals use parent's metadata."
  [parent node]
  (or (-> node meta :line)
      (-> parent meta :line)))

(defn- node-column
  "Extract column number for a mutation site.
   Symbols get reader metadata; literals use parent's metadata."
  [parent node]
  (or (-> node meta :column)
      (-> parent meta :column)))

(defn- walk-children
  "Recurse into child nodes of any collection type."
  [walk-fn grandparent parent node]
  (cond
    (seq? node) (doseq [child node] (walk-fn parent node child))
    (vector? node) (doseq [child node] (walk-fn parent node child))
    (map? node) (doseq [[k v] node] (walk-fn parent node k) (walk-fn parent node v))
    (set? node) (doseq [child node] (walk-fn parent node child))))

(defn find-mutations
  "Walk form tree, return vector of mutation sites.
   Each site: {:index N :original form :mutant form :description \"...\"}.
   SYNC WARNING: find-mutations and apply-mutation must walk the tree
   identically so mutation indices match. Any change to traversal order,
   grandparent tracking, or suppression logic must be mirrored in both."
  [form]
  (let [counter (atom 0)
        sites (atom [])]
    (letfn [(walk [grandparent parent node]
              (let [context {:parent parent :grandparent grandparent}]
                (when-let [rule (first-matching-rule context node)]
                  (swap! sites conj {:index @counter
                                     :original (:original rule)
                                     :mutant (:mutant rule)
                                     :category (:category rule)
                                     :line (node-line parent node)
                                     :column (node-column parent node)
                                     :description (str (:original rule) " -> " (:mutant rule))})
                  (swap! counter inc))
                (walk-children walk grandparent parent node)))]
      (walk nil nil form))
    @sites))

(defn- rebuild-coll
  "Rebuild a collection after walking its children."
  [walk-fn grandparent parent node]
  (cond
    (seq? node) (apply list (map #(walk-fn parent node %) node))
    (vector? node) (mapv #(walk-fn parent node %) node)
    (map? node) (into {} (map (fn [[k v]] [(walk-fn parent node k) (walk-fn parent node v)]) node))
    (set? node) (into #{} (map #(walk-fn parent node %) node))
    :else node))

(defn apply-mutation
  "Walk form tree, apply the mutation at the given index.
   Returns the mutated form.
   SYNC WARNING: find-mutations and apply-mutation must walk the tree
   identically so mutation indices match. Any change to traversal order,
   grandparent tracking, or suppression logic must be mirrored in both."
  [form target-index]
  (let [counter (atom 0)]
    (letfn [(walk [grandparent parent node]
              (let [context {:parent parent :grandparent grandparent}]
                (if-let [rule (first-matching-rule context node)]
                  (let [idx @counter]
                    (swap! counter inc)
                    (if (= idx target-index)
                      (if (seq? node)
                        (let [mutant (:mutant rule)
                              new-parent (cons mutant (rest node))]
                          (apply list mutant (map #(walk parent new-parent %) (rest node))))
                        (:mutant rule))
                      (rebuild-coll walk grandparent parent node)))
                  (rebuild-coll walk grandparent parent node))))]
      (walk nil nil form))))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-03-12T09:09:43.20757-05:00", :module-hash "-1432221243", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line 1, :hash "-739386167"} {:id "defn-/rand-comparison?", :kind "defn-", :line 3, :end-line 11, :hash "2022929343"} {:id "defn-/rand-nth-guard-form?", :kind "defn-", :line 13, :end-line 26, :hash "-1742139724"} {:id "defn-/rand-nth-single-element-guard?", :kind "defn-", :line 28, :end-line 33, :hash "1173485497"} {:id "defn-/inside-rand-nth-literal?", :kind "defn-", :line 35, :end-line 42, :hash "-871002365"} {:id "defn-/subvec-trim-boundary?", :kind "defn-", :line 44, :end-line 54, :hash "-1009745996"} {:id "def/rules", :kind "def", :line 56, :end-line 75, :hash "-771035529"} {:id "defn/matches-rule?", :kind "defn", :line 77, :end-line 89, :hash "110835417"} {:id "defn-/first-matching-rule", :kind "defn-", :line 91, :end-line 92, :hash "2106982202"} {:id "defn-/node-line", :kind "defn-", :line 94, :end-line 99, :hash "-291343130"} {:id "defn-/node-column", :kind "defn-", :line 101, :end-line 106, :hash "-941899799"} {:id "defn-/walk-children", :kind "defn-", :line 108, :end-line 115, :hash "-162145999"} {:id "defn/find-mutations", :kind "defn", :line 117, :end-line 139, :hash "-1879045609"} {:id "defn-/rebuild-coll", :kind "defn-", :line 141, :end-line 149, :hash "-1973189765"} {:id "defn/apply-mutation", :kind "defn", :line 151, :end-line 172, :hash "-135468684"}]}
;; clj-mutate-manifest-end
