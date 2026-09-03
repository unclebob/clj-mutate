(ns clj-mutate.selection
  (:require [clj-mutate.manifest :as manifest]))

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

(defn count-changed-sites
  [all-sites prior-manifest forms]
  (cond
    (nil? prior-manifest) (count all-sites)
    (= (:module-hash prior-manifest) (manifest/module-hash forms)) 0
    :else (count (filter-by-form-indices all-sites (manifest/changed-form-indices forms prior-manifest)))))

(defn differential-site-counts
  [sites new-form-indices manifest-violating-form-indices]
  {:new-form-mutations (count (filter #(contains? new-form-indices (:form-index %)) sites))
   :manifest-violating-form-mutations (count (filter #(contains? manifest-violating-form-indices (:form-index %)) sites))})

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-09-02T15:15:00.296994-05:00", :module-hash "604214296", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line nil, :hash "1621961677"} {:id "defn-/filter-sites-by", :kind "defn-", :line 4, :end-line nil, :hash "341209114"} {:id "defn/filter-by-lines", :kind "defn", :line 10, :end-line nil, :hash "-479853682"} {:id "defn/filter-by-form-indices", :kind "defn", :line 14, :end-line nil, :hash "-1270395709"} {:id "defn/default-since-last-run?", :kind "defn", :line 18, :end-line nil, :hash "-71751391"} {:id "defn/select-mutation-sites", :kind "defn", :line 24, :end-line nil, :hash "-1457564386"} {:id "defn/count-changed-sites", :kind "defn", :line 32, :end-line nil, :hash "-1978221242"} {:id "defn/differential-site-counts", :kind "defn", :line 39, :end-line nil, :hash "1294887939"}]}
;; clj-mutate-manifest-end
