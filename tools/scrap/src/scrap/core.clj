(ns scrap.core
  (:require [clj-mutate.source :as source]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(def assertion-heads
  '#{should should= should-not should-not= should-contain should-not-contain
     should-be-nil should-not-be-nil should-throw})

(def branch-heads
  '#{if if-not when when-not cond case and or try doseq for loop while})

(def setup-heads
  '#{let binding with-redefs before before-all around with with-stubs})

(def speclj-forms
  #{"describe" "context" "it" "before" "before-all" "after" "with-stubs" "with" "around"})

(def default-top-example-count 5)

(def default-top-block-count 3)

(defn- combine-metrics
  [& metrics]
  (reduce
    (fn [acc metric]
      {:assertions (+ (:assertions acc 0) (:assertions metric 0))
       :branches (+ (:branches acc 0) (:branches metric 0))
       :with-redefs (+ (:with-redefs acc 0) (:with-redefs metric 0))
       :helper-calls (+ (:helper-calls acc 0) (:helper-calls metric 0))
       :temp-resources (+ (:temp-resources acc 0) (:temp-resources metric 0))
       :large-literals (+ (:large-literals acc 0) (:large-literals metric 0))
       :max-setup-depth (max (:max-setup-depth acc 0) (:max-setup-depth metric 0))})
    {:assertions 0
     :branches 0
     :with-redefs 0
     :helper-calls 0
     :temp-resources 0
     :large-literals 0
     :max-setup-depth 0}
    metrics))

(defn- seq-head [expr]
  (when (seq? expr)
    (first expr)))

(defn- symbol-name [x]
  (when (symbol? x)
    (name x)))

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
        (#{"sh"} n)
        (and n (str/includes? n "Temp")))))

(defn- analyze-node
  [expr helper-set setup-depth]
  (let [head (seq-head expr)
        next-depth (if (contains? setup-heads head)
                     (inc setup-depth)
                     setup-depth)
        children (cond
                   (seq? expr) (seq expr)
                   (map? expr) (concat (keys expr) (vals expr))
                   (coll? expr) (seq expr)
                   :else nil)
        child-metrics (map #(analyze-node % helper-set next-depth) children)
        local {:assertions (if (contains? assertion-heads head) 1 0)
               :branches (if (contains? branch-heads head) 1 0)
               :with-redefs (if (= 'with-redefs head) 1 0)
               :helper-calls (if (contains? helper-set head) 1 0)
               :temp-resources (if (temp-resource-call? head) 1 0)
               :large-literals (if (large-literal? expr) 1 0)
               :max-setup-depth setup-depth}]
    (apply combine-metrics local child-metrics)))

(defn- top-level-phase
  [expr helper-set]
  (let [head (seq-head expr)
        metrics (analyze-node expr helper-set 0)]
    (cond
      (contains? setup-heads head) :setup
      (pos? (:assertions metrics)) :assert
      :else :action)))

(defn- assertion-clusters
  [body helper-set]
  (->> body
       (map #(top-level-phase % helper-set))
       (partition-by identity)
       (filter #(= :assert (first %)))
       count))

(defn- helper-symbols
  [forms]
  (->> forms
       (keep (fn [form]
               (let [head (seq-head form)]
                 (when (and (#{'defn 'defn- 'defmacro} head)
                            (symbol? (second form)))
                   (second form)))))
       set))

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

(defn- arrange-forms
  [forms helper-set]
  (filterv #(not= :assert (top-level-phase % helper-set)) forms))

(defn- score-example
  [it-form helper-set inherited-setup-forms describe-path]
  (let [[_ name & body] it-form
        inherited-setup (count inherited-setup-forms)
        metrics (apply combine-metrics (map #(analyze-node % helper-set inherited-setup) body))
        line-count (max 1 (inc (- (or (-> it-form meta :end-line) (-> it-form meta :line) 1)
                                  (or (-> it-form meta :line) 1))))
        local-setup (setup-forms body)
        effective-setup (vec (concat inherited-setup-forms local-setup))
        setup-signatures (form-signatures effective-setup)
        setup-features (form-features effective-setup)
        arrange-signatures (form-signatures (arrange-forms body helper-set))
        arrange-features (form-features (arrange-forms body helper-set))
        literal-signatures (vec (mapcat collect-large-literal-signatures body))
        literal-features (set literal-signatures)
        fixture-shape (when (seq setup-signatures) (shape-signature setup-signatures))
        fixture-features (if (seq setup-signatures)
                           (shape-features setup-signatures)
                           #{})
        phases (assertion-clusters body helper-set)
        complexity (+ 1
                      (:branches metrics)
                      (:max-setup-depth metrics)
                      (:helper-calls metrics))
        smell-entries (cond-> []
                        (zero? (:assertions metrics))
                        (conj {:label "no-assertions" :penalty 10})

                        (and (= 1 (:assertions metrics)) (> line-count 10))
                        (conj {:label "low-assertion-density" :penalty 6})

                        (> phases 1)
                        (conj {:label "multiple-phases" :penalty 5})

                        (> (:with-redefs metrics) 3)
                        (conj {:label "high-mocking" :penalty 4})

                        (> line-count 20)
                        (conj {:label "large-example" :penalty 4})

                        (pos? (:temp-resources metrics))
                        (conj {:label "temp-resource-work" :penalty 3})

                        (pos? (:large-literals metrics))
                        (conj {:label "literal-heavy-setup" :penalty 3}))
        smell-penalty (reduce + (map :penalty smell-entries))
        scrap (+ (* complexity complexity) smell-penalty)]
    {:name name
     :describe-path describe-path
     :line (or (-> it-form meta :line) 1)
     :line-count line-count
     :assertions (:assertions metrics)
     :branches (:branches metrics)
     :setup-depth (:max-setup-depth metrics)
     :with-redefs (:with-redefs metrics)
     :helper-calls (:helper-calls metrics)
     :temp-resources (:temp-resources metrics)
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
  [forms helper-set describe-path inherited-setup-forms]
  (let [local-setup (setup-forms forms)
        effective-setup (vec (concat inherited-setup-forms local-setup))]
    (mapcat
      (fn [form]
        (let [head (seq-head form)]
          (cond
            (#{'describe 'context} head)
            (let [[_ name & body] form]
              (collect-examples body helper-set (conj describe-path name) effective-setup))

            (= 'it head)
            [(score-example form helper-set effective-setup describe-path)]

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

(def duplication-threshold 0.5)

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
  (let [pairs (for [left examples
                    right examples
                    :when (< (.indexOf examples left) (.indexOf examples right))]
                (jaccard-similarity (key-fn left) (key-fn right)))]
    (if (seq pairs)
      (/ (reduce + pairs) (count pairs))
      0.0)))

(defn- distinct-shape-count
  [examples key-fn]
  (count (distinct (remove nil? (mapcat key-fn examples)))))

(defn- summarize-duplication
  [examples]
  (let [repeated-setup-examples (similar-example-count examples :setup-features)
        repeated-fixture-examples (similar-example-count examples :fixture-features)
        repeated-literal-examples (similar-example-count examples :literal-features)
        repeated-arrange-examples (similar-example-count examples :arrange-features)]
    {:repeated-setup-examples repeated-setup-examples
     :repeated-fixture-examples repeated-fixture-examples
     :repeated-literal-examples repeated-literal-examples
     :repeated-arrange-examples repeated-arrange-examples
     :setup-shape-diversity (distinct-shape-count examples :setup-signatures)
     :literal-shape-diversity (distinct-shape-count examples :literal-signatures)
     :arrange-shape-diversity (distinct-shape-count examples :arrange-signatures)
     :avg-setup-similarity (average-similarity examples :setup-features)
     :avg-fixture-similarity (average-similarity examples :fixture-features)
     :avg-literal-similarity (average-similarity examples :literal-features)
     :avg-arrange-similarity (average-similarity examples :arrange-features)
     :duplication-score (+ repeated-setup-examples
                           repeated-fixture-examples
                           repeated-literal-examples
                           repeated-arrange-examples)}))

(defn- summarize-examples
  [examples]
  (let [total (count examples)
        avg-scrap (if (pos? total)
                    (/ (reduce + (map :scrap examples)) total)
                    0.0)]
    (merge
      {:example-count total
       :avg-scrap avg-scrap
       :max-scrap (if (seq examples) (apply max (map :scrap examples)) 0)
       :branching-examples (count (filter #(pos? (:branches %)) examples))
       :low-assertion-examples (count (filter #(<= (:assertions %) 1) examples))
       :with-redefs-examples (count (filter #(pos? (:with-redefs %)) examples))}
      (summarize-duplication examples))))

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

(defn- refactor-pressure-score
  [summary]
  (+ (* 1.2 (or (:avg-scrap summary) 0))
     (* 0.6 (or (:max-scrap summary) 0))
     (* 0.8 (or (:duplication-score summary) 0))
     (* 20 (ratio (or (:low-assertion-examples summary) 0) (or (:example-count summary) 0)))
     (* 15 (ratio (or (:branching-examples summary) 0) (or (:example-count summary) 0)))
     (* 15 (ratio (or (:with-redefs-examples summary) 0) (or (:example-count summary) 0)))))

(defn- pressure-level
  [score]
  (cond
    (>= score 55) "CRITICAL"
    (>= score 35) "HIGH"
    (>= score 18) "MEDIUM"
    :else "LOW"))

(defn- recommendation-actions
  [summary]
  (cond-> []
    (> (or (:duplication-score summary) 0) 0)
    (conj "Extract shared setup or arrange scaffolding.")

    (> (ratio (or (:with-redefs-examples summary) 0) (or (:example-count summary) 0)) 0.3)
    (conj "Reduce mocking and move coverage toward higher-level behaviors.")

    (> (ratio (or (:low-assertion-examples summary) 0) (or (:example-count summary) 0)) 0.4)
    (conj "Strengthen assertions in weak examples.")

    (> (ratio (or (:branching-examples summary) 0) (or (:example-count summary) 0)) 0.3)
    (conj "Remove logic from specs or convert variation into table/data-driven checks.")

    (> (or (:max-scrap summary) 0) 20)
    (conj "Split oversized examples into narrower examples.")

    (> (or (:avg-scrap summary) 0) 12)
    (conj "Consider splitting this file or block by responsibility.")))

(defn- guidance
  [{:keys [summary blocks examples]}]
  (let [file-score (refactor-pressure-score summary)
        sorted-blocks (sort-by #(refactor-pressure-score (:summary %)) > blocks)
        sorted-examples (sort-by :scrap > examples)]
    {:file-score file-score
     :file-level (pressure-level file-score)
     :actions (vec (take 4 (distinct (recommendation-actions summary))))
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

(defn analyze-source
  [source-text path]
  (let [structure-errors (scan-structure source-text)
        forms (try
                (source/read-source-forms source-text)
                (catch Exception ex
                  {:parse-error (.getMessage ex)}))]
    (if (map? forms)
      {:path path
       :structure-errors structure-errors
       :parse-error (:parse-error forms)
       :examples []}
      (let [helpers (helper-symbols forms)
            examples (vec (collect-examples forms helpers [] []))
            summary (summarize-examples examples)]
        {:path path
         :structure-errors structure-errors
         :parse-error nil
         :examples examples
         :summary summary
         :blocks (summarize-blocks examples)}))))

(defn analyze-file
  [path]
  (analyze-source (slurp path) path))

(defn- parse-args
  [args]
  {:verbose (boolean (some #{"--verbose"} args))
   :paths (vec (remove #{"--verbose"} args))})

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

(defn- format-smells [smells]
  (if (seq smells)
    (str/join ", " smells)
    "none"))

(defn- render-guidance-report
  [{:keys [path summary blocks examples] :as report}]
  (let [{:keys [file-score file-level actions top-blocks top-examples]} (guidance report)
        why-section
        (str "  why:\n"
             "    avg-scrap: " (format "%.1f" (double (or (:avg-scrap summary) 0.0))) "\n"
             "    max-scrap: " (or (:max-scrap summary) 0) "\n"
             "    duplication-score: " (or (:duplication-score summary) 0) "\n"
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
                        (pressure-level (refactor-pressure-score summary))
                        ", avg-scrap "
                        (format "%.1f" (double (or (:avg-scrap summary) 0.0)))
                        ", duplication "
                        (or (:duplication-score summary) 0)
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
               (str/join "\n" (map #(str "    " %) actions))
               "\n"))]
    (str
      path "\n"
      "  refactor-pressure: " file-level " (" (format "%.1f" (double file-score)) ")\n"
      why-section
      where-section
      worst-section
      how-section)))

(defn- render-file-report
  [{:keys [path structure-errors parse-error examples summary blocks]}]
  (str
    path "\n"
    (when (seq structure-errors)
      (str "  structure-errors:\n"
           (str/join "\n" (map #(str "    " %) structure-errors))
           "\n"))
    (when parse-error
      (str "  parse-error: " parse-error "\n"))
    (when summary
      (str "  avg-scrap: " (format "%.1f" (double (or (:avg-scrap summary) 0.0))) "\n"
           "  max-scrap: " (:max-scrap summary) "\n"
           "  branching-examples: " (:branching-examples summary) "/" (:example-count summary) "\n"
           "  low-assertion-examples: " (:low-assertion-examples summary) "/" (:example-count summary) "\n"
           "  with-redefs-examples: " (:with-redefs-examples summary) "/" (:example-count summary) "\n"
           "  duplication-score: " (or (:duplication-score summary) 0) "\n"
           "  repeated-setup-examples: " (or (:repeated-setup-examples summary) 0) "/" (:example-count summary) "\n"
           "  repeated-fixture-examples: " (or (:repeated-fixture-examples summary) 0) "/" (:example-count summary) "\n"
           "  repeated-literal-examples: " (or (:repeated-literal-examples summary) 0) "/" (:example-count summary) "\n"
           "  repeated-arrange-examples: " (or (:repeated-arrange-examples summary) 0) "/" (:example-count summary) "\n"
           "  setup-shape-diversity: " (or (:setup-shape-diversity summary) 0) "\n"
           "  literal-shape-diversity: " (or (:literal-shape-diversity summary) 0) "\n"
           "  arrange-shape-diversity: " (or (:arrange-shape-diversity summary) 0) "\n"
           "  avg-setup-similarity: " (format "%.2f" (double (or (:avg-setup-similarity summary) 0.0))) "\n"
           "  avg-fixture-similarity: " (format "%.2f" (double (or (:avg-fixture-similarity summary) 0.0))) "\n"
           "  avg-literal-similarity: " (format "%.2f" (double (or (:avg-literal-similarity summary) 0.0))) "\n"
           "  avg-arrange-similarity: " (format "%.2f" (double (or (:avg-arrange-similarity summary) 0.0))) "\n"
           (when (seq examples)
             (let [top-example (first (sort-by :scrap > examples))]
               (str "  worst-example: "
                    (str/join " / " (conj (:describe-path top-example) (:name top-example)))
                    " (SCRAP " (:scrap top-example) ")\n")))))
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
                    "      duplication-score: " (or (:duplication-score summary) 0) "\n"
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
                    "      assertions: " (:assertions example) "\n"
                    "      branches: " (:branches example) "\n"
                    "      setup-depth: " (:setup-depth example) "\n"
                    "      redefs: " (:with-redefs example) "\n"
                    "      helper-calls: " (:helper-calls example) "\n"
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

(defn -main
  [& args]
  (let [{:keys [paths verbose]} (parse-args args)
        files (collect-spec-files paths)
        reports (mapv analyze-file files)
        has-errors? (some #(or (seq (:structure-errors %)) (:parse-error %)) reports)]
    (println (render-report reports verbose))
    (shutdown-agents)
    (System/exit (if has-errors? 1 0))))
