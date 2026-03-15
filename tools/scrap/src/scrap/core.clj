(ns scrap.core
  (:require [clj-mutate.source :as source]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(def assertion-heads
  '#{should should= should-not should-not= should-contain should-not-contain
     should-be-nil should-not-be-nil should-throw})

(def branch-heads
  '#{if if-not when when-not cond case and or try loop while})

(def table-branch-heads
  '#{doseq for every? map mapv run!})

(def setup-heads
  '#{let binding with-redefs before before-all around with with-stubs})

(def helper-def-heads
  '#{defn defn- defmacro})

(def speclj-form-symbols
  '#{describe context it before before-all after with-stubs with around run-specs})

(def speclj-forms
  (set (map name speclj-form-symbols)))

(def default-top-example-count 5)
(def default-top-block-count 3)
(def duplication-threshold 0.5)
(def baseline-version 1)

(defn- combine-metrics
  [& metrics]
  (reduce
    (fn [acc metric]
      {:assertions (+ (:assertions acc 0) (:assertions metric 0))
       :branches (+ (:branches acc 0) (:branches metric 0))
       :table-branches (+ (:table-branches acc 0) (:table-branches metric 0))
       :with-redefs (+ (:with-redefs acc 0) (:with-redefs metric 0))
       :helper-calls (+ (:helper-calls acc 0) (:helper-calls metric 0))
       :helper-hidden-lines (+ (:helper-hidden-lines acc 0) (:helper-hidden-lines metric 0))
       :temp-resources (+ (:temp-resources acc 0) (:temp-resources metric 0))
       :large-literals (+ (:large-literals acc 0) (:large-literals metric 0))
       :max-setup-depth (max (:max-setup-depth acc 0) (:max-setup-depth metric 0))
       :subject-symbols (set/union (:subject-symbols acc #{}) (:subject-symbols metric #{}))
       :table-driven? (or (:table-driven? acc false) (:table-driven? metric false))})
    {:assertions 0
     :branches 0
     :table-branches 0
     :with-redefs 0
     :helper-calls 0
     :helper-hidden-lines 0
     :temp-resources 0
     :large-literals 0
     :max-setup-depth 0
     :subject-symbols #{}
     :table-driven? false}
    metrics))

(defn- seq-head [expr]
  (when (seq? expr)
    (first expr)))

(defn- symbol-name [x]
  (when (symbol? x)
    (name x)))

(defn- namespaced-symbol? [x]
  (and (symbol? x) (namespace x)))

(defn- large-literal?
  [expr]
  (or (and (string? expr) (> (count (str/split-lines expr)) 5))
      (and (map? expr) (> (count expr) 10))
      (and (vector? expr) (> (count expr) 10))
      (and (set? expr) (> (count expr) 10))))

(defn- temp-resource-call?
  [head]
  (let [n (symbol-name head)]
    (or (#{"createTempFile" "createTempDirectory" "mkdir" "mkdirs" "future"} n)
        (#{"sh" "slurp" "spit"} n)
        (and n (str/includes? n "Temp")))))

(defn- control-symbol?
  [sym]
  (or (contains? assertion-heads sym)
      (contains? setup-heads sym)
      (contains? branch-heads sym)
      (contains? table-branch-heads sym)
      (contains? speclj-form-symbols sym)
      (= 'fn sym)
      (= 'fn* sym)
      (= 'quote sym)
      (= 'var sym)
      (= '. sym)))

(defn- large-case-table?
  [expr]
  (and (or (vector? expr) (list? expr))
       (>= (count expr) 2)
       (every? coll? expr)))

(declare normalize-shape)

(defn- normalized-map-shape
  [m]
  (into (sorted-map)
        (for [[k v] m]
          [(normalize-shape k) (normalize-shape v)])))

(defn- normalize-shape
  [expr]
  (cond
    (seq? expr) (apply list (map normalize-shape expr))
    (vector? expr) (mapv normalize-shape expr)
    (map? expr) (normalized-map-shape expr)
    (set? expr) (into #{} (map normalize-shape expr))
    (symbol? expr) 'sym
    (keyword? expr) expr
    (string? expr) :string
    (number? expr) :number
    (char? expr) :char
    (boolean? expr) :boolean
    (nil? expr) nil
    :else (type expr)))

(defn- shape-signature
  [expr]
  (pr-str (normalize-shape expr)))

(defn- shape-features
  [expr]
  (let [normalized (normalize-shape expr)
        children (cond
                   (seq? normalized) (seq normalized)
                   (map? normalized) (concat (keys normalized) (vals normalized))
                   (coll? normalized) (seq normalized)
                   :else nil)]
    (conj (set (mapcat shape-features children))
          (pr-str normalized))))

(defn- collect-large-literal-signatures
  [expr]
  (let [children (cond
                   (seq? expr) (seq expr)
                   (map? expr) (concat (keys expr) (vals expr))
                   (coll? expr) (seq expr)
                   :else nil)
        local (when (large-literal? expr)
                [(shape-signature expr)])]
    (vec (concat local (mapcat collect-large-literal-signatures children)))))

(defn- form-signatures
  [forms]
  (mapv shape-signature forms))

(defn- form-features
  [forms]
  (apply set/union #{} (map shape-features forms)))

(defn- setup-forms
  [forms]
  (filterv #(contains? setup-heads (seq-head %)) forms))

(declare analyze-node)

(defn- top-level-phase
  [expr helper-context]
  (let [head (seq-head expr)
        metrics (analyze-node expr helper-context 0 #{})]
    (cond
      (contains? setup-heads head) :setup
      (pos? (:assertions metrics)) :assert
      :else :action)))

(defn- assert-forms
  [forms helper-context]
  (filterv #(= :assert (top-level-phase % helper-context)) forms))

(defn- arrange-forms
  [forms helper-context]
  (filterv #(not= :assert (top-level-phase % helper-context)) forms))

(defn- assertion-clusters
  [body helper-context]
  (->> body
       (map #(top-level-phase % helper-context))
       (partition-by identity)
       (filter #(= :assert (first %)))
       count))

(defn- non-helper-body
  [form]
  (let [[_ _ & more] form]
    (cond
      (string? (first more)) (recur (cons nil (cons nil (rest more))))
      (map? (first more)) (recur (cons nil (cons nil (rest more))))
      :else more)))

(defn- collect-helper-defs
  [forms]
  (letfn [(walk [form]
            (let [head (seq-head form)
                  nested (cond
                           (seq? form) (mapcat walk (seq form))
                           (map? form) (mapcat walk (concat (keys form) (vals form)))
                           (coll? form) (mapcat walk form)
                           :else [])]
              (if (and (contains? helper-def-heads head)
                       (symbol? (second form)))
                (cons [(second form) (non-helper-body form)] nested)
                nested)))]
    (into {} (mapcat walk forms))))

(defn- helper-line-span
  [body]
  (reduce
    (fn [span form]
      (+ span
         (max 1 (inc (- (or (-> form meta :end-line) (-> form meta :line) 1)
                        (or (-> form meta :line) 1))))))
    0
    body))

(defn- table-driven-form?
  [expr]
  (let [head (seq-head expr)
        children (cond
                   (seq? expr) (seq expr)
                   (map? expr) (concat (keys expr) (vals expr))
                   (coll? expr) (seq expr)
                   :else nil)]
    (or (and (contains? table-branch-heads head)
             (some large-case-table? children))
        (and (contains? assertion-heads head)
             (some large-case-table? children))
        (some table-driven-form? children))))

(declare helper-expanded-metrics)

(defn- analyze-node
  [expr helper-context setup-depth helper-stack]
  (let [{:keys [helper-defs helper-cache]} helper-context
        head (seq-head expr)
        helper-call? (and (symbol? head) (contains? helper-defs head))
        next-depth (if (contains? setup-heads head)
                     (inc setup-depth)
                     setup-depth)
        children (cond
                   (seq? expr) (seq expr)
                   (map? expr) (concat (keys expr) (vals expr))
                   (coll? expr) (seq expr)
                   :else nil)
        child-metrics (map #(analyze-node % helper-context next-depth helper-stack) children)
        helper-metrics (if (and helper-call? (not (contains? helper-stack head)))
                         (helper-expanded-metrics helper-context head (conj helper-stack head) setup-depth)
                         nil)
        subject-symbols (cond
                          (namespaced-symbol? head) #{head}
                          (and (symbol? head)
                               (not helper-call?)
                               (not (control-symbol? head)))
                          #{head}
                          :else #{})
        local {:assertions (if (contains? assertion-heads head) 1 0)
               :branches (if (contains? branch-heads head) 1 0)
               :table-branches (if (contains? table-branch-heads head) 1 0)
               :with-redefs (if (= 'with-redefs head) 1 0)
               :helper-calls (if helper-call? 1 0)
               :helper-hidden-lines 0
               :temp-resources (if (temp-resource-call? head) 1 0)
               :large-literals (if (large-literal? expr) 1 0)
               :max-setup-depth setup-depth
               :subject-symbols subject-symbols
               :table-driven? (table-driven-form? expr)}]
    (apply combine-metrics
           local
           (concat child-metrics (when helper-metrics [helper-metrics])))))

(defn- helper-expanded-metrics
  [{:keys [helper-defs helper-cache] :as helper-context} helper-sym helper-stack setup-depth]
  (if-let [cached (get @helper-cache [helper-sym setup-depth])]
    cached
    (let [body (get helper-defs helper-sym)
          metrics (if (seq body)
                    (apply combine-metrics
                           {:helper-hidden-lines (helper-line-span body)}
                           (map #(analyze-node % helper-context setup-depth helper-stack) body))
                    {:helper-hidden-lines 0})]
      (swap! helper-cache assoc [helper-sym setup-depth] metrics)
      metrics)))

(defn- helper-context
  [forms]
  {:helper-defs (collect-helper-defs forms)
   :helper-cache (atom {})})

(defn- api-contract-example?
  [metrics line-count phases]
  (and
       (<= (:assertions metrics) 2)
       (<= (+ (:branches metrics) (:table-branches metrics)) 4)
       (<= (:table-branches metrics) 1)
       (<= (:with-redefs metrics) 0)
       (<= (:helper-calls metrics) 1)
       (<= (:helper-hidden-lines metrics) 0)
       (<= (:temp-resources metrics) 0)
       (<= (:large-literals metrics) 0)
       (<= (count (:subject-symbols metrics)) 4)
       (<= phases 1)
       (<= line-count 18)))

(defn- score-example
  [it-form helper-context inherited-setup-forms describe-path]
  (let [[_ name & body] it-form
        inherited-setup (count inherited-setup-forms)
        metrics (apply combine-metrics (map #(analyze-node % helper-context inherited-setup #{}) body))
        raw-line-count (max 1 (inc (- (or (-> it-form meta :end-line) (-> it-form meta :line) 1)
                                      (or (-> it-form meta :line) 1))))
        line-count (+ raw-line-count (:helper-hidden-lines metrics))
        local-setup (setup-forms body)
        effective-setup (vec (concat inherited-setup-forms local-setup))
        assert-signatures (form-signatures (assert-forms body helper-context))
        assert-features (form-features (assert-forms body helper-context))
        setup-signatures (form-signatures effective-setup)
        setup-features (form-features effective-setup)
        arrange-signatures (form-signatures (arrange-forms body helper-context))
        arrange-features (form-features (arrange-forms body helper-context))
        literal-signatures (vec (mapcat collect-large-literal-signatures body))
        literal-features (set literal-signatures)
        fixture-shape (when (seq setup-signatures) (shape-signature setup-signatures))
        fixture-features (if (seq setup-signatures)
                           (shape-features setup-signatures)
                           #{})
        phases (assertion-clusters body helper-context)
        api-contract? (api-contract-example? metrics line-count phases)
        scored-setup-depth (if api-contract?
                             (max 0 (- (:max-setup-depth metrics) 2))
                             (:max-setup-depth metrics))
        complexity (+ 1
                      (:branches metrics)
                      scored-setup-depth
                      (:helper-calls metrics)
                      (quot (:helper-hidden-lines metrics) 8))
        table-driven? (:table-driven? metrics)
        smell-entries (cond-> []
                        (zero? (:assertions metrics))
                        (conj {:label "no-assertions" :penalty 10})

                        (and (= 1 (:assertions metrics)) (> line-count 10) (not table-driven?) (not api-contract?))
                        (conj {:label "low-assertion-density" :penalty 6})

                        (> phases 1)
                        (conj {:label "multiple-phases" :penalty 5})

                        (> (:with-redefs metrics) 3)
                        (conj {:label "high-mocking" :penalty 4})

                        (and (> line-count 20) (not api-contract?))
                        (conj {:label "large-example" :penalty 4})

                        (pos? (:temp-resources metrics))
                        (conj {:label "temp-resource-work" :penalty 3})

                        (pos? (:large-literals metrics))
                        (conj {:label "literal-heavy-setup" :penalty 3})

                        (> (:helper-hidden-lines metrics) 8)
                        (conj {:label "helper-hidden-complexity" :penalty 4}))
        smell-penalty (reduce + (map :penalty smell-entries))
        branch-penalty (if table-driven?
                         (:table-branches metrics)
                         (+ (:branches metrics) (:table-branches metrics)))
        scored-branch-penalty (if api-contract?
                                (max 0 (- branch-penalty 2))
                                branch-penalty)
        scrap (+ (* (+ 1 scored-branch-penalty scored-setup-depth (:helper-calls metrics))
                    (+ 1 scored-branch-penalty scored-setup-depth (:helper-calls metrics)))
                 smell-penalty)]
    {:name name
     :describe-path describe-path
     :line (or (-> it-form meta :line) 1)
     :line-count line-count
     :raw-line-count raw-line-count
     :assertions (:assertions metrics)
     :branches (+ (:branches metrics) (:table-branches metrics))
     :setup-depth (:max-setup-depth metrics)
     :with-redefs (:with-redefs metrics)
     :helper-calls (:helper-calls metrics)
     :helper-hidden-lines (:helper-hidden-lines metrics)
     :temp-resources (:temp-resources metrics)
     :table-driven? table-driven?
     :api-contract? api-contract?
     :subject-symbols (:subject-symbols metrics)
     :assert-signatures assert-signatures
     :assert-features assert-features
     :setup-signatures setup-signatures
     :setup-features setup-features
     :fixture-shape fixture-shape
     :fixture-features fixture-features
     :arrange-signatures arrange-signatures
     :arrange-features arrange-features
     :literal-signatures literal-signatures
     :literal-features literal-features
     :scrap scrap
     :smells (mapv :label smell-entries)}))

(defn- collect-examples
  [forms helper-context describe-path inherited-setup-forms]
  (let [local-setup (setup-forms forms)
        effective-setup (vec (concat inherited-setup-forms local-setup))]
    (mapcat
      (fn [form]
        (let [head (seq-head form)]
          (cond
            (#{'describe 'context} head)
            (let [[_ name & body] form]
              (collect-examples body helper-context (conj describe-path name) effective-setup))

            (= 'it head)
            [(score-example form helper-context effective-setup describe-path)]

            :else [])))
      forms)))

(defn- jaccard-similarity
  [a b]
  (let [a (or a #{})
        b (or b #{})
        union-size (count (set/union a b))]
    (if (zero? union-size)
      0.0
      (/ (count (set/intersection a b))
         union-size))))

(defn- similar-example-count
  [examples key-fn]
  (count
    (filter
      (fn [example]
        (let [features (key-fn example)]
          (and (seq features)
               (some #(>= (jaccard-similarity features (key-fn %)) duplication-threshold)
                     (remove #{example} examples)))))
      examples)))

(defn- average-similarity
  [examples key-fn]
  (let [pairs (for [[idx left] (map-indexed vector examples)
                    right (drop (inc idx) examples)]
                (jaccard-similarity (key-fn left) (key-fn right)))]
    (if (seq pairs)
      (/ (reduce + pairs) (count pairs))
      0.0)))

(defn- distinct-shape-count
  [examples key-fn]
  (count (distinct (remove nil? (mapcat key-fn examples)))))

(defn- coverage-matrix-candidate?
  [example]
  (and (:table-driven? example)
       (<= (:scrap example) 18)
       (<= (:line-count example) 18)
       (<= (:setup-depth example) 2)
       (<= (:with-redefs example) 1)))

(defn- similar-to-any?
  [example examples key-fn]
  (let [features (key-fn example)]
    (and (seq features)
         (some #(>= (jaccard-similarity features (key-fn %)) duplication-threshold)
               (remove #{example} examples)))))

(defn- coverage-matrix-count
  [examples]
  (count
    (filter
      (fn [example]
        (and (coverage-matrix-candidate? example)
             (or (similar-to-any? example examples :setup-features)
                 (similar-to-any? example examples :assert-features)
                 (similar-to-any? example examples :arrange-features))))
      examples)))

(defn- summarize-duplication
  [examples]
  (let [setup-duplication-score (similar-example-count examples :setup-features)
        assertion-duplication-score (similar-example-count examples :assert-features)
        fixture-duplication-score (similar-example-count examples :fixture-features)
        literal-duplication-score (similar-example-count examples :literal-features)
        arrange-duplication-score (similar-example-count examples :arrange-features)
        subject-repetition-score (similar-example-count examples :subject-symbols)]
    {:setup-duplication-score setup-duplication-score
     :assertion-duplication-score assertion-duplication-score
     :fixture-duplication-score fixture-duplication-score
     :literal-duplication-score literal-duplication-score
     :arrange-duplication-score arrange-duplication-score
     :repeated-setup-examples setup-duplication-score
     :repeated-fixture-examples fixture-duplication-score
     :repeated-literal-examples literal-duplication-score
     :repeated-arrange-examples arrange-duplication-score
     :subject-repetition-score subject-repetition-score
     :setup-shape-diversity (distinct-shape-count examples :setup-signatures)
     :assert-shape-diversity (distinct-shape-count examples :assert-signatures)
     :literal-shape-diversity (distinct-shape-count examples :literal-signatures)
     :arrange-shape-diversity (distinct-shape-count examples :arrange-signatures)
     :avg-setup-similarity (average-similarity examples :setup-features)
     :avg-assert-similarity (average-similarity examples :assert-features)
     :avg-fixture-similarity (average-similarity examples :fixture-features)
     :avg-literal-similarity (average-similarity examples :literal-features)
     :avg-arrange-similarity (average-similarity examples :arrange-features)
     :avg-subject-similarity (average-similarity examples :subject-symbols)
     :duplication-score (+ setup-duplication-score
                           assertion-duplication-score
                           fixture-duplication-score
                           literal-duplication-score
                           arrange-duplication-score)
     :harmful-duplication-score (+ setup-duplication-score
                                   assertion-duplication-score
                                   fixture-duplication-score
                                   arrange-duplication-score)}))

(defn- summarize-examples
  [examples]
  (let [total (count examples)
        avg-scrap (if (pos? total)
                    (/ (reduce + (map :scrap examples)) total)
                    0.0)
        duplication (summarize-duplication examples)
        coverage-matrix-candidates (coverage-matrix-count examples)
        helper-hidden-example-count (count (filter #(pos? (:helper-hidden-lines %)) examples))
        zero-assertion-examples (count (filter #(zero? (:assertions %)) examples))
        table-driven-examples (count (filter :table-driven? examples))]
    (merge
      {:example-count total
       :avg-scrap avg-scrap
       :max-scrap (if (seq examples) (apply max (map :scrap examples)) 0)
       :branching-examples (count (filter #(and (pos? (:branches %))
                                                (not (:table-driven? %)))
                                          examples))
       :low-assertion-examples (count (filter #(<= (:assertions %) 1) examples))
       :with-redefs-examples (count (filter #(pos? (:with-redefs %)) examples))
       :zero-assertion-examples zero-assertion-examples
       :helper-hidden-example-count helper-hidden-example-count
       :table-driven-examples table-driven-examples
       :coverage-matrix-candidates coverage-matrix-candidates}
      duplication
      {:case-matrix-repetition coverage-matrix-candidates
       :coverage-matrix-candidates coverage-matrix-candidates
       :effective-duplication-score (max 0 (- (:harmful-duplication-score duplication)
                                              coverage-matrix-candidates))})))

(defn- summarize-blocks
  [examples]
  (->> examples
       (group-by :describe-path)
       (remove (fn [[path _]] (empty? path)))
       (map (fn [[path block-examples]]
              {:path path
               :summary (summarize-examples block-examples)
               :worst-example (first (sort-by :scrap > block-examples))}))
       (sort-by (fn [{:keys [path]}]
                  [(count path) (str/join " / " path)]))
       vec))

(defn- ratio
  [n d]
  (if (pos? d) (/ n d) 0.0))

(defn- stable-summary?
  [summary]
  (let [example-count (or (:example-count summary) 0)
        zero-assertion-ratio (ratio (or (:zero-assertion-examples summary) 0) example-count)
        low-assertion-ratio (ratio (or (:low-assertion-examples summary) 0) example-count)]
    (or (and (<= example-count 2)
             (<= (or (:max-scrap summary) 0) 10)
             (<= (or (:effective-duplication-score summary) 0) 1)
             (zero? (or (:helper-hidden-example-count summary) 0))
             (<= zero-assertion-ratio 0.0))
        (and (pos? example-count)
             (<= (or (:max-scrap summary) 0) 12)
             (<= (or (:effective-duplication-score summary) 0) 3)
             (<= zero-assertion-ratio 0.0)
             (<= low-assertion-ratio 0.35)))))

(defn- size-factor
  [summary]
  (let [example-count (or (:example-count summary) 0)]
    (cond
      (<= example-count 1) 0.25
      (<= example-count 2) 0.40
      (<= example-count 4) 0.65
      :else 1.0)))

(defn- refactor-pressure-score
  [summary]
  (let [base (+ (* 1.2 (or (:avg-scrap summary) 0))
                (* 0.6 (or (:max-scrap summary) 0))
                (* 0.8 (or (:effective-duplication-score summary) 0))
                (* 20 (ratio (or (:low-assertion-examples summary) 0) (or (:example-count summary) 0)))
                (* 15 (ratio (or (:branching-examples summary) 0) (or (:example-count summary) 0)))
                (* 15 (ratio (or (:with-redefs-examples summary) 0) (or (:example-count summary) 0)))
                (* 12 (ratio (or (:helper-hidden-example-count summary) 0) (or (:example-count summary) 0))))
        matrix-credit (* 4 (or (:case-matrix-repetition summary) 0))]
    (- (* (size-factor summary) base) matrix-credit)))

(defn- pressure-level
  [summary]
  (let [score (refactor-pressure-score summary)]
    (cond
      (stable-summary? summary) "STABLE"
      (>= score 55) "CRITICAL"
      (>= score 35) "HIGH"
      (>= score 18) "MEDIUM"
      :else "LOW")))

(defn- recommendation
  [confidence text]
  {:confidence confidence
   :label ({3 "HIGH" 2 "MEDIUM" 1 "LOW"} confidence)
   :text text})

(defn- recommendation-actions
  [summary]
  (if (stable-summary? summary)
    [(recommendation 3 "No refactor recommended; the file is structurally stable enough to leave alone.")]
    (let [example-count (or (:example-count summary) 0)
          low-assertion-ratio (ratio (or (:low-assertion-examples summary) 0) example-count)
          zero-assertion-ratio (ratio (or (:zero-assertion-examples summary) 0) example-count)
          branching-ratio (ratio (or (:branching-examples summary) 0) example-count)
          mocking-ratio (ratio (or (:with-redefs-examples summary) 0) example-count)
          harmful-duplication (or (:effective-duplication-score summary) 0)]
      (->> (cond-> []
             (pos? (or (:coverage-matrix-candidates summary) 0))
             (conj (recommendation 3 "Convert repeated low-complexity examples into table-driven checks; treat this as coverage-matrix repetition, not harmful duplication."))

             (or (> zero-assertion-ratio 0.0) (> low-assertion-ratio 0.4))
             (conj (recommendation 3 "Strengthen assertions in weak examples before doing structural cleanup."))

             (> (or (:max-scrap summary) 0) 20)
             (conj (recommendation 3 "Split oversized examples into narrower examples."))

             (> harmful-duplication 0)
             (conj (recommendation 2 "Extract shared setup or repeated assertion scaffolding only where harmful duplication is dominating."))

             (> mocking-ratio 0.3)
             (conj (recommendation 2 "Reduce mocking and move coverage toward higher-level behaviors."))

             (> branching-ratio 0.3)
             (conj (recommendation 2 "Remove logic from specs or keep variation in explicit data tables rather than control flow."))

             (> (or (:helper-hidden-example-count summary) 0) 0)
             (conj (recommendation 1 "Be skeptical of helper extraction that only hides setup; helper-hidden complexity should still count as complexity."))

             (> (or (:avg-scrap summary) 0) 12)
             (conj (recommendation 1 "Consider splitting this file or block by responsibility.")))
           (sort-by (juxt (comp - :confidence) :text))
           vec))))

(defn- guidance
  [{:keys [summary blocks examples]}]
  (let [file-score (refactor-pressure-score summary)
        sorted-blocks (sort-by #(refactor-pressure-score (:summary %)) > blocks)
        sorted-examples (sort-by :scrap > examples)]
    {:file-score file-score
     :file-level (pressure-level summary)
     :actions (vec (take 4 (recommendation-actions summary)))
     :top-blocks (vec (take default-top-block-count sorted-blocks))
     :top-examples (vec (take default-top-example-count sorted-examples))}))

(defn- process-char [state c next-c]
  (let [{:keys [mode depth line escape skip]} state]
    (cond
      skip (assoc state :skip false)
      escape (assoc state :escape false)
      (= mode :comment) (if (= c \newline)
                          (assoc state :mode :normal :line (inc line))
                          state)
      (= mode :string) (cond
                         (= c \\) (assoc state :escape true)
                         (= c \") (assoc state :mode :normal)
                         (= c \newline) (update state :line inc)
                         :else state)
      (= mode :regex) (cond
                        (= c \\) (assoc state :escape true)
                        (= c \") (assoc state :mode :normal)
                        (= c \newline) (update state :line inc)
                        :else state)
      (= c \;) (assoc state :mode :comment)
      (= c \\) (assoc state :escape true)
      (= c \") (assoc state :mode :string)
      (and (= c \#) (= next-c \")) (assoc state :mode :regex :skip true)
      (= c \newline) (update state :line inc)
      (= c \() (update state :depth inc)
      (= c \)) (update state :depth dec)
      :else state)))

(def token-delimiters #{\space \newline \tab \( \) \"})

(defn- extract-token [chars i]
  (let [n (count chars)
        start (inc i)]
    (when (< start n)
      (let [end (reduce (fn [_ j]
                          (if (token-delimiters (nth chars j))
                            (reduced j)
                            (inc j)))
                        start
                        (range start n))]
        (when (> end start)
          (apply str (subvec chars start end)))))))

(defn- validate-nesting [form line form-stack]
  (when-let [parent (peek form-stack)]
    (let [parent-form (:form parent)]
      (cond
        (= parent-form "it")
        (str "ERROR line " line ": (" form ") inside (it) at line " (:line parent))

        (and (= parent-form "describe") (= form "describe"))
        (str "ERROR line " line ": (describe) inside (describe) at line " (:line parent))

        (and (= parent-form "context") (= form "describe"))
        (str "ERROR line " line ": (describe) inside (context) at line " (:line parent))

        (and (= parent-form "it") (#{"before" "with-stubs" "around" "with" "context"} form))
        (str "ERROR line " line ": (" form ") inside (it) at line " (:line parent))))))

(defn- pop-form [form-stack]
  (let [completed (peek form-stack)
        stack (pop form-stack)]
    (assoc completed :stack stack)))

(defn scan-structure
  [text]
  (let [chars (vec text)
        n (count chars)
        init {:mode :normal :depth 0 :line 1 :escape false :skip false :errors [] :form-stack []}
        result (reduce
                 (fn [state i]
                   (let [c (nth chars i)
                         next-c (when (< (inc i) n) (nth chars (inc i)))
                         old-depth (:depth state)
                         old-mode (:mode state)
                         state (process-char state c next-c)
                         new-depth (:depth state)]
                     (cond
                       (and (= old-mode :normal) (= c \() (> new-depth old-depth))
                       (let [token (extract-token chars i)]
                         (if (and token (speclj-forms token))
                           (let [error (validate-nesting token (:line state) (:form-stack state))]
                             (cond-> state
                               error (update :errors conj error)
                               true (update :form-stack conj {:form token :line (:line state) :depth old-depth})))
                           state))

                       (and (= old-mode :normal) (= c \)) (< new-depth old-depth)
                            (seq (:form-stack state))
                            (= new-depth (:depth (peek (:form-stack state)))))
                       (assoc state :form-stack (:stack (pop-form (:form-stack state))))

                       :else state)))
                 init
                 (range n))
        eof-line (:line result)
        unclosed-errors (mapv (fn [entry]
                                (str "ERROR line " eof-line ": unclosed (" (:form entry)
                                     ") from line " (:line entry)))
                              (:form-stack result))]
    (into (:errors result) unclosed-errors)))

(defn- content-hash
  [s]
  (format "%08x" (bit-and 0xffffffff (hash s))))

(defn analyze-source
  [source-text path]
  (let [structure-errors (scan-structure source-text)
        forms (try
                (source/read-source-forms source-text)
                (catch Exception ex
                  {:parse-error (.getMessage ex)}))]
    (if (map? forms)
      {:path path
       :content-hash (content-hash source-text)
       :structure-errors structure-errors
       :parse-error (:parse-error forms)
       :examples []}
      (let [helpers (helper-context forms)
            examples (vec (collect-examples forms helpers [] []))
            summary (summarize-examples examples)]
        {:path path
         :content-hash (content-hash source-text)
         :structure-errors structure-errors
         :parse-error nil
         :examples examples
         :summary summary
         :blocks (summarize-blocks examples)}))))

(defn analyze-file
  [path]
  (analyze-source (slurp path) path))

(defn- spec-file?
  [^java.io.File f]
  (and (.isFile f)
       (or (str/ends-with? (.getName f) "_spec.clj")
           (str/ends-with? (.getName f) "_spec.cljc"))))

(defn collect-spec-files
  [paths]
  (let [roots (if (seq paths) paths ["spec"])]
    (->> roots
         (map io/file)
         (mapcat (fn [f]
                   (cond
                     (.isFile f) [f]
                     (.isDirectory f) (filter spec-file? (file-seq f))
                     :else [])))
         (filter spec-file?)
         (sort-by #(.getPath ^java.io.File %))
         (mapv #(.getPath ^java.io.File %)))))

(defn baseline-output-path
  [paths]
  (let [roots (if (seq paths) paths ["spec"])
        name-part (-> (str/join "_" roots)
                      (str/replace #"[^A-Za-z0-9._-]+" "_")
                      (str/replace #"^_+" "")
                      (str/replace #"_+$" ""))]
    (str "target/scrap/" (if (seq name-part) name-part "spec") ".json")))

(defn baseline-document
  [paths reports]
  {:baseline-version baseline-version
   :paths (vec paths)
   :reports (mapv #(select-keys % [:path :content-hash :summary :structure-errors :parse-error]) reports)})

(defn- write-baseline!
  [path doc]
  (.mkdirs (io/file "target/scrap"))
  (spit path (json/write-str doc))
  path)

(defn- read-json-file
  [path]
  (json/read-str (slurp path) :key-fn keyword))

(defn- delta
  [before after]
  (- (or after 0) (or before 0)))

(defn- verdict
  [comparison]
  (cond
    (and (<= (:file-score-delta comparison) -5)
         (<= (:harmful-duplication-delta comparison) 0)
         (<= (:max-scrap-delta comparison) 0))
    :improved

    (and (pos? (:helper-hidden-delta comparison))
         (>= (:harmful-duplication-delta comparison) 0)
         (<= (:case-matrix-delta comparison) 0))
    :worse

    (or (pos? (:harmful-duplication-delta comparison))
        (pos? (:max-scrap-delta comparison))
        (>= (:file-score-delta comparison) 5))
    :worse

    (zero? (:file-score-delta comparison))
    :unchanged

    :else :mixed))

(defn compare-reports
  [baseline-doc reports]
  (let [baseline-by-path (into {} (map (juxt :path identity) (:reports baseline-doc)))]
    (mapv
      (fn [report]
        (let [baseline (get baseline-by-path (:path report))
              current-summary (:summary report)
              baseline-summary (:summary baseline)
              comparison (when (and baseline-summary current-summary)
                           (let [file-score-before (refactor-pressure-score baseline-summary)
                                 file-score-after (refactor-pressure-score current-summary)
                                 cmp {:baseline-hash (:content-hash baseline)
                                      :current-hash (:content-hash report)
                                      :file-score-delta (delta file-score-before file-score-after)
                                      :avg-scrap-delta (delta (:avg-scrap baseline-summary) (:avg-scrap current-summary))
                                      :max-scrap-delta (delta (:max-scrap baseline-summary) (:max-scrap current-summary))
                                      :harmful-duplication-delta (delta (:harmful-duplication-score baseline-summary)
                                                                        (:harmful-duplication-score current-summary))
                                      :case-matrix-delta (delta (:case-matrix-repetition baseline-summary)
                                                                (:case-matrix-repetition current-summary))
                                      :helper-hidden-delta (delta (:helper-hidden-example-count baseline-summary)
                                                                  (:helper-hidden-example-count current-summary))}]
                             (assoc cmp :verdict (verdict cmp))))]
          (assoc report :comparison comparison)))
      reports)))

(defn- parse-args
  [args]
  (loop [opts {:verbose false
               :json false
               :write-baseline false
               :compare nil
               :paths []}
         remaining args]
    (if-let [arg (first remaining)]
      (cond
        (= arg "--verbose")
        (recur (assoc opts :verbose true) (rest remaining))

        (= arg "--json")
        (recur (assoc opts :json true) (rest remaining))

        (= arg "--write-baseline")
        (recur (assoc opts :write-baseline true) (rest remaining))

        (= arg "--compare")
        (recur (assoc opts :compare (second remaining)) (nnext remaining))

        :else
        (recur (update opts :paths conj arg) (rest remaining)))
      opts)))

(defn- format-smells [smells]
  (if (seq smells)
    (str/join ", " smells)
    "none"))

(defn- render-comparison
  [{:keys [comparison]}]
  (when comparison
    (str "  comparison:\n"
         "    verdict: " (name (:verdict comparison)) "\n"
         "    file-score-delta: " (format "%.1f" (double (:file-score-delta comparison))) "\n"
         "    avg-scrap-delta: " (format "%.1f" (double (:avg-scrap-delta comparison))) "\n"
         "    max-scrap-delta: " (:max-scrap-delta comparison) "\n"
         "    harmful-duplication-delta: " (:harmful-duplication-delta comparison) "\n"
         "    case-matrix-delta: " (:case-matrix-delta comparison) "\n"
         "    helper-hidden-delta: " (:helper-hidden-delta comparison) "\n"
         (when (= :worse (:verdict comparison))
           "    recommendation: Refactor appears negative; consider reverting or simplifying helper extraction.\n"))))

(defn- render-guidance-report
  [{:keys [path summary blocks examples] :as report}]
  (let [{:keys [file-score file-level actions top-blocks top-examples]} (guidance report)
        why-section
        (str "  why:\n"
             "    avg-scrap: " (format "%.1f" (double (or (:avg-scrap summary) 0.0))) "\n"
             "    max-scrap: " (or (:max-scrap summary) 0) "\n"
             "    harmful-duplication-score: " (or (:harmful-duplication-score summary) 0) "\n"
             "    effective-duplication-score: " (or (:effective-duplication-score summary) 0) "\n"
             "    coverage-matrix-candidates: " (or (:coverage-matrix-candidates summary) 0) "\n"
             "    case-matrix-repetition: " (or (:case-matrix-repetition summary) 0) "\n"
             "    subject-repetition-score: " (or (:subject-repetition-score summary) 0) "\n"
             "    helper-hidden-example-count: " (or (:helper-hidden-example-count summary) 0) "\n"
             "    low-assertion-ratio: "
             (format "%.2f" (double (ratio (or (:low-assertion-examples summary) 0)
                                           (or (:example-count summary) 0))))
             "\n"
             "    branching-ratio: "
             (format "%.2f" (double (ratio (or (:branching-examples summary) 0)
                                           (or (:example-count summary) 0))))
             "\n"
             "    mocking-ratio: "
             (format "%.2f" (double (ratio (or (:with-redefs-examples summary) 0)
                                           (or (:example-count summary) 0))))
             "\n")
        where-section
        (when (seq top-blocks)
          (str "  where:\n"
               (str/join
                 "\n"
                 (for [{:keys [path summary worst-example]} top-blocks]
                   (str "    "
                        (str/join " / " path)
                        " -> "
                        (pressure-level summary)
                        ", avg-scrap "
                        (format "%.1f" (double (or (:avg-scrap summary) 0.0)))
                        ", harmful duplication "
                        (or (:harmful-duplication-score summary) 0)
                        ", worst "
                        (:name worst-example)
                        " (SCRAP " (:scrap worst-example) ")")))
               "\n"))
        worst-section
        (when (seq top-examples)
          (str "  worst-examples:\n"
               (str/join
                 "\n"
                 (for [example top-examples]
                   (str "    "
                        (str/join " / " (conj (:describe-path example) (:name example)))
                        " -> SCRAP " (:scrap example)
                        (when (seq (:smells example))
                          (str " [" (format-smells (:smells example)) "]")))))
               "\n"))
        how-section
        (when (seq actions)
          (str "  how:\n"
               (str/join "\n" (map #(str "    " (:label %) ": " (:text %)) actions))
               "\n"))]
    (str
      path "\n"
      "  refactor-pressure: " file-level " (" (format "%.1f" (double file-score)) ")\n"
      why-section
      (render-comparison report)
      where-section
      worst-section
      how-section)))

(defn- render-file-report
  [{:keys [path structure-errors parse-error examples summary blocks] :as report}]
  (str
    path "\n"
    (when (seq structure-errors)
      (str "  structure-errors:\n"
           (str/join "\n" (map #(str "    " %) structure-errors))
           "\n"))
    (when parse-error
      (str "  parse-error: " parse-error "\n"))
    (render-comparison report)
    (when summary
      (str "  avg-scrap: " (format "%.1f" (double (or (:avg-scrap summary) 0.0))) "\n"
           "  max-scrap: " (:max-scrap summary) "\n"
           "  branching-examples: " (:branching-examples summary) "/" (:example-count summary) "\n"
           "  low-assertion-examples: " (:low-assertion-examples summary) "/" (:example-count summary) "\n"
           "  zero-assertion-examples: " (:zero-assertion-examples summary) "/" (:example-count summary) "\n"
           "  with-redefs-examples: " (:with-redefs-examples summary) "/" (:example-count summary) "\n"
           "  duplication-score: " (or (:duplication-score summary) 0) "\n"
           "  harmful-duplication-score: " (or (:harmful-duplication-score summary) 0) "\n"
           "  effective-duplication-score: " (or (:effective-duplication-score summary) 0) "\n"
           "  coverage-matrix-candidates: " (or (:coverage-matrix-candidates summary) 0) "\n"
           "  case-matrix-repetition: " (or (:case-matrix-repetition summary) 0) "\n"
           "  subject-repetition-score: " (or (:subject-repetition-score summary) 0) "\n"
           "  helper-hidden-example-count: " (or (:helper-hidden-example-count summary) 0) "\n"
           "  setup-duplication-score: " (or (:setup-duplication-score summary) 0) "\n"
           "  assertion-duplication-score: " (or (:assertion-duplication-score summary) 0) "\n"
           "  fixture-duplication-score: " (or (:fixture-duplication-score summary) 0) "\n"
           "  literal-duplication-score: " (or (:literal-duplication-score summary) 0) "\n"
           "  arrange-duplication-score: " (or (:arrange-duplication-score summary) 0) "\n"
           "  avg-setup-similarity: " (format "%.2f" (double (or (:avg-setup-similarity summary) 0.0))) "\n"
           "  avg-assert-similarity: " (format "%.2f" (double (or (:avg-assert-similarity summary) 0.0))) "\n"
           "  avg-arrange-similarity: " (format "%.2f" (double (or (:avg-arrange-similarity summary) 0.0))) "\n"))
    (when (seq blocks)
      (str "\n  blocks:\n"
           (str/join
             "\n"
             (for [{:keys [path summary worst-example]} blocks]
               (str "    "
                    (str/join " / " path) "\n"
                    "      examples: " (:example-count summary) "\n"
                    "      avg-scrap: " (format "%.1f" (double (or (:avg-scrap summary) 0.0))) "\n"
                    "      max-scrap: " (:max-scrap summary) "\n"
                    "      harmful-duplication-score: " (or (:harmful-duplication-score summary) 0) "\n"
                    "      coverage-matrix-candidates: " (or (:coverage-matrix-candidates summary) 0) "\n"
                    "      case-matrix-repetition: " (or (:case-matrix-repetition summary) 0) "\n"
                    "      worst-example: " (:name worst-example) " (SCRAP " (:scrap worst-example) ")")))))
    (when (seq examples)
      (str "\n"
           (str/join
             "\n"
             (for [example (take 5 (sort-by :scrap > examples))]
               (str "    "
                    (str/join " / " (conj (:describe-path example) (:name example))) "\n"
                    "      SCRAP: " (:scrap example) "\n"
                    "      lines: " (:line-count example) "\n"
                    "      raw-lines: " (or (:raw-line-count example) 0) "\n"
                    "      assertions: " (:assertions example) "\n"
                    "      branches: " (:branches example) "\n"
                    "      setup-depth: " (:setup-depth example) "\n"
                    "      redefs: " (:with-redefs example) "\n"
                    "      helper-calls: " (:helper-calls example) "\n"
                    "      helper-hidden-lines: " (or (:helper-hidden-lines example) 0) "\n"
                    "      table-driven: " (if (:table-driven? example) "yes" "no") "\n"
                    "      smells: " (format-smells (:smells example)))))))))

(defn render-report
  [file-reports verbose?]
  (let [worst (->> file-reports
                   (mapcat (fn [{:keys [path examples]}]
                             (map #(assoc % :file path) examples)))
                   (sort-by :scrap >)
                   (take 10))]
    (str
      "=== SCRAP Report ===\n\n"
      (str/join "\n\n" (map (if verbose? render-file-report render-guidance-report) file-reports))
      (when (seq worst)
        (str "\n\nWorst Examples:\n"
             (str/join
               "\n"
               (map-indexed
                 (fn [idx example]
                   (str "  " (inc idx) ". "
                        (:file example) " :: "
                        (str/join " / " (conj (:describe-path example) (:name example)))
                        "  SCRAP " (:scrap example)))
                 worst)))))))

(defn- render-json
  [paths reports]
  (json/write-str
    {:baseline-version baseline-version
     :paths (vec paths)
     :reports reports}))

(defn -main
  [& args]
  (let [{:keys [paths verbose json write-baseline compare]} (parse-args args)
        files (collect-spec-files paths)
        reports (mapv analyze-file files)
        reports (if compare
                  (compare-reports (read-json-file compare) reports)
                  reports)
        baseline-doc (baseline-document paths reports)
        baseline-path (baseline-output-path paths)
        has-errors? (some #(or (seq (:structure-errors %)) (:parse-error %)) reports)]
    (when write-baseline
      (write-baseline! baseline-path baseline-doc))
    (println (if json
               (render-json paths reports)
               (render-report reports verbose)))
    (when write-baseline
      (println (str "Baseline written: " baseline-path)))
    (shutdown-agents)
    (System/exit (if has-errors? 1 0))))
