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

(defn default-since-last-run?
  [lines since-last-run mutate-all prior-manifest]
  (and (nil? lines)
       (not mutate-all)
       (or since-last-run (some? prior-manifest))))

(defn select-mutation-sites
  [covered-sites lines since-last-run module-unchanged? changed-forms]
  (cond
    lines (filter-by-lines covered-sites lines)
    module-unchanged? []
    since-last-run (filter-by-form-indices covered-sites changed-forms)
    :else covered-sites))

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
