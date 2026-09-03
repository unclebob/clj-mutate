(ns clj-mutate.source
  (:require [clojure.string :as str]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]
            [clj-mutate.mutations :as mutations]
            [clj-mutate.syntax :as syntax]
            [rewrite-clj.zip :as z]))

(defn read-source-forms
  [source-str]
  (let [rdr (reader-types/source-logging-push-back-reader source-str)
        opts {:read-cond :allow :features #{:clj} :eof ::eof}]
    (loop [forms []]
      (let [form (reader/read opts rdr)]
        (if (= ::eof form)
          forms
          (recur (conj forms form)))))))

(defn discover-all-mutations
  "Discover mutations in already-read forms. This compatibility API is useful
   for semantic rule tests; executable mutations should be discovered from
   source text with discover-mutations so they have exact syntax paths."
  [forms]
  (vec (mapcat
         (fn [idx form]
           (map #(assoc % :form-index idx)
                (mutations/find-mutations form)))
         (range) forms)))

(defn partition-by-coverage
  [sites covered-lines]
  (let [has-line? #(some? (:line %))
        covered? (if (nil? covered-lines)
                   has-line?
                   #(and (has-line? %) (contains? covered-lines (:line %))))
        grouped (group-by covered? sites)]
    [(vec (get grouped true [])) (vec (get grouped false []))]))

(defn- ancestor-sexpr
  [zloc levels]
  (loop [loc zloc
         remaining levels]
    (if (zero? remaining)
      (syntax/sexpr loc)
      (recur (z/up loc) (dec remaining)))))

(defn- rule-name
  [rule-id]
  (str (namespace rule-id) "/" (name rule-id)))

(defn- mutation-identity
  [form-id form-path rule-id]
  (str form-id "/"
       (str/join "." form-path) "/"
       (rule-name rule-id)))

(defn- discover-syntax-mutations
  [source-text]
  (let [root (syntax/of-source source-text)
        form-ids (->> (syntax/top-level-locations root)
                      (mapv syntax/sexpr)
                      syntax/top-level-form-ids)
        form-counters (atom {})]
    (loop [loc root
           sites []]
      (if (z/end? loc)
        sites
        (let [node (syntax/sexpr loc)
              parent (ancestor-sexpr loc 1)
              context {:parent parent
                       :grandparent (ancestor-sexpr loc 2)
                       :great-grandparent (ancestor-sexpr loc 3)}
              path (syntax/semantic-path loc)
              form-index (first path)
              rule (when (and (some? node) (some? form-index))
                     (mutations/matching-rule context node))]
          (if rule
            (let [local-index (get @form-counters form-index 0)
                  _ (swap! form-counters update form-index (fnil inc 0))
                  form-id (nth form-ids form-index)
                  form-path (vec (rest path))
                  position (syntax/position loc)
                  site (merge position
                              {:index local-index
                               :form-index form-index
                               :form-id form-id
                               :node-path path
                               :semantic-path form-path
                               :rule-id (:id rule)
                               :mutation-id (mutation-identity form-id form-path (:id rule))
                               :original (:original rule)
                               :mutant (:mutant rule)
                               :category (:category rule)
                               :description (str (:original rule) " -> " (:mutant rule))})]
              (recur (z/next loc) (conj sites site)))
            (recur (z/next loc) sites)))))))

(defn token-pattern
  [token]
  (let [s (str token)]
    (or ({"="    (re-pattern "(?<![><=!])=(?!=)")
          "not=" (re-pattern "not=")
          ">"    (re-pattern ">(?!=)")
          ">="   (re-pattern ">=")
          "<"    (re-pattern "<(?!=)")
          "<="   (re-pattern "<=")} s)
        (when (re-matches #"\d+" s)
          (re-pattern (str "(?<!\\d|\\.)" (java.util.regex.Pattern/quote s) "(?!\\d|\\.)")))
        (when (re-matches #"[a-zA-Z].*" s)
          (re-pattern (str "(?<![a-zA-Z0-9_-])" (java.util.regex.Pattern/quote s) "(?![a-zA-Z0-9_-])")))
        (re-pattern (str "(?<=[\\s(])" (java.util.regex.Pattern/quote s) "(?=[\\s)])")))))

(defn mutate-source-text
  [original-content site]
  (let [path (:node-path site)]
    (when-not (seq path)
      (throw (ex-info "Mutation site has no exact syntax path"
                      {:site site :reason :invalid-mutation-location})))
    (let [root (syntax/of-source original-content)
          target (syntax/location-at-path root path)
          actual (syntax/sexpr target)]
      (when-not (= (:original site) actual)
        (throw (ex-info "Mutation target does not match the discovered source node"
                        {:site site
                         :actual actual
                         :reason :mutation-target-mismatch})))
      (let [normalized-result (syntax/replace-source target (:mutant site))
            crlf? (and (str/includes? original-content "\r\n")
                       (not (re-find #"(?<!\r)\n" original-content)))
            result (if crlf?
                     (str/replace normalized-result "\n" "\r\n")
                     normalized-result)]
        (when (= original-content result)
          (throw (ex-info "Mutation rendering did not change the source"
                          {:site site :reason :no-op-mutation})))
        result))))

(defn discover-mutations
  "Discover executable mutation sites directly from source text. Every site
   has an exact syntax path, a deterministic identity, and a file-global label.
   Identical rendered mutants are deduplicated before execution."
  [source-text]
  (let [sites (discover-syntax-mutations source-text)
        unique-sites
        (:sites
          (reduce
            (fn [{:keys [seen sites] :as state} site]
              (let [mutated-source (mutate-source-text source-text site)]
                (if (contains? seen mutated-source)
                  state
                  {:seen (conj seen mutated-source)
                   :sites (conj sites site)})))
            {:seen #{} :sites []}
            sites))]
    (mapv (fn [run-index site]
            (assoc site
                   :run-index run-index
                   :display-id (format "M%03d" (inc run-index))))
          (range)
          unique-sites)))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-09-02T15:18:05.69112-05:00", :module-hash "1160566186", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line nil, :hash "-1894966203"} {:id "defn/read-source-forms", :kind "defn", :line 7, :end-line nil, :hash "1432776152"} {:id "defn/discover-all-mutations", :kind "defn", :line 17, :end-line nil, :hash "602452094"} {:id "defn/partition-by-coverage", :kind "defn", :line 25, :end-line nil, :hash "821677293"} {:id "defn/token-pattern", :kind "defn", :line 33, :end-line nil, :hash "1327827280"} {:id "defn/mutate-source-text", :kind "defn", :line 48, :end-line nil, :hash "1783741911"}]}
;; clj-mutate-manifest-end
