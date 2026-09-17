(ns clj-mutate.selection)

(defn- filter-sites-by
  [sites allowed-values key-fn]
  (if allowed-values
    (vec (filter #(contains? allowed-values (key-fn %)) sites))
    sites))

(defn filter-by-lines
  [sites lines]
  (filter-sites-by sites lines :line))

(defn filter-by-form-indices
  [sites form-indices]
  (filter-sites-by sites form-indices :form-index))

(defn filter-by-mutation
  [sites mutation-selector]
  (if mutation-selector
    (vec (filter #(or (= mutation-selector (:display-id %))
                      (= mutation-selector (:mutation-id %)))
                 sites))
    sites))

(defn default-since-last-run?
  [lines since-last-run mutate-all prior-manifest]
  (and (nil? lines)
       (not mutate-all)
       (or since-last-run (map? prior-manifest))))

(defn select-mutation-sites
  ([covered-sites lines since-last-run module-unchanged? changed-forms]
   (select-mutation-sites covered-sites lines nil since-last-run module-unchanged? changed-forms))
  ([covered-sites lines mutation-selector since-last-run module-unchanged? changed-forms]
   (cond
     mutation-selector (filter-by-mutation covered-sites mutation-selector)
     lines (filter-by-lines covered-sites lines)
     module-unchanged? []
     since-last-run (filter-by-form-indices covered-sites changed-forms)
     :else covered-sites)))

(defn differential-site-counts
  [sites new-form-indices manifest-violating-form-indices]
  {:new-form-mutations (count (filter #(contains? new-form-indices (:form-index %)) sites))
   :manifest-violating-form-mutations (count (filter #(contains? manifest-violating-form-indices (:form-index %)) sites))})

