(ns clj-mutate.manifest
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io File]))

(def mutation-comment-re #"^;; mutation-tested: (\d{4}-\d{2}-\d{2})")
(def manifest-start-line ";; clj-mutate-manifest-begin")
(def manifest-end-line ";; clj-mutate-manifest-end")
(def manifest-block-re
  (re-pattern
    (str "(?ms)\n?"
         (java.util.regex.Pattern/quote manifest-start-line)
         "\n(.*?)\n"
         (java.util.regex.Pattern/quote manifest-end-line)
         "\n?$")))

(declare extract-embedded-manifest)

(defn extract-mutation-date
  [content]
  (or (when-let [manifest (:tested-at (extract-embedded-manifest content))]
        manifest)
      (when-let [m (re-find mutation-comment-re content)]
        (second m))))

(defn stamp-mutation-date
  [content date-str]
  (let [comment-line (str ";; mutation-tested: " date-str)]
    (if (re-find mutation-comment-re content)
      (str/replace content mutation-comment-re comment-line)
      (str comment-line "\n" content))))

(defn extract-embedded-manifest
  [content]
  (when-let [[_ raw-body] (re-find manifest-block-re content)]
    (->> (str/split-lines raw-body)
         (map #(str/replace % #"^;; ?" ""))
         (str/join "\n")
         edn/read-string)))

(defn strip-embedded-manifest
  [content]
  (str/replace content manifest-block-re ""))

(defn strip-mutation-metadata
  [content]
  (-> content
      strip-embedded-manifest
      (str/replace #"(?m)^;; mutation-tested: \d{4}-\d{2}-\d{2}\n?" "")))

(defn- form-kind
  [form]
  (when (seq? form)
    (first form)))

(defn- top-level-form-id
  [idx form]
  (let [head (form-kind form)]
    (cond
      (and (#{'def 'defn 'defn- 'defmacro 'defmulti} head)
           (symbol? (second form)))
      (str head "/" (second form))

      (and (= 'defmethod head)
           (symbol? (second form)))
      (str head "/" (second form) "/" (pr-str (nth form 2 nil)))

      :else
      (str "form/" idx "/" (or head :literal)))))

(defn top-level-form-manifest
  [forms]
  (mapv
    (fn [idx form]
      {:id (top-level-form-id idx form)
       :kind (str (or (form-kind form) :literal))
       :line (-> form meta :line)
       :end-line (-> form meta :end-line)
       :hash (str (hash (pr-str form)))})
    (range)
    forms))

(defn module-hash
  [forms]
  (str (hash (pr-str forms))))

(defn changed-form-indices
  [forms manifest]
  (let [current (top-level-form-manifest forms)
        previous-by-id (into {} (map (juxt :id identity) (:forms manifest)))]
    (->> current
         (keep-indexed
           (fn [idx form-entry]
             (let [previous (get previous-by-id (:id form-entry))]
               (when (or (nil? previous)
                         (not= (:hash previous) (:hash form-entry)))
                 idx))))
         set)))

(defn build-embedded-manifest
  [forms date-str]
  {:version 1
   :tested-at date-str
   :module-hash (module-hash forms)
   :forms (top-level-form-manifest forms)})

(defn embed-mutation-manifest
  [content manifest]
  (let [base (strip-mutation-metadata content)
        body (->> (pr-str manifest)
                  str/split-lines
                  (map #(str ";; " %))
                  (str/join "\n"))]
    (str (str/trimr base)
         "\n\n"
         manifest-start-line
         "\n"
         body
         "\n"
         manifest-end-line
         "\n")))

(defn- backup-path
  [source-path]
  (str source-path ".mutation-backup"))

(defn save-backup!
  [source-path content]
  (spit (backup-path source-path) content))

(defn restore-from-backup!
  [source-path]
  (let [bp (backup-path source-path)]
    (when (.exists (File. bp))
      (spit source-path (slurp bp))
      (.delete (File. bp))
      true)))

(defn cleanup-backup!
  [source-path]
  (let [f (File. (backup-path source-path))]
    (when (.exists f)
      (.delete f))))

(defn now-str
  []
  (.format (java.time.OffsetDateTime/now)
           java.time.format.DateTimeFormatter/ISO_OFFSET_DATE_TIME))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-03-12T09:09:42.922069-05:00", :module-hash "127438113", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line 4, :hash "-1861788327"} {:id "def/mutation-comment-re", :kind "def", :line 6, :end-line 6, :hash "739874186"} {:id "def/manifest-start-line", :kind "def", :line 7, :end-line 7, :hash "-1825565512"} {:id "def/manifest-end-line", :kind "def", :line 8, :end-line 8, :hash "744098285"} {:id "def/manifest-block-re", :kind "def", :line 9, :end-line 15, :hash "-1957750279"} {:id "form/5/declare", :kind "declare", :line 17, :end-line 17, :hash "668603200"} {:id "defn/extract-mutation-date", :kind "defn", :line 19, :end-line 24, :hash "-569806568"} {:id "defn/stamp-mutation-date", :kind "defn", :line 26, :end-line 31, :hash "-1761741461"} {:id "defn/extract-embedded-manifest", :kind "defn", :line 33, :end-line 39, :hash "-838193478"} {:id "defn/strip-embedded-manifest", :kind "defn", :line 41, :end-line 43, :hash "1175673264"} {:id "defn/strip-mutation-metadata", :kind "defn", :line 45, :end-line 49, :hash "1841423544"} {:id "defn-/form-kind", :kind "defn-", :line 51, :end-line 54, :hash "184035403"} {:id "defn-/top-level-form-id", :kind "defn-", :line 56, :end-line 69, :hash "317205413"} {:id "defn/top-level-form-manifest", :kind "defn", :line 71, :end-line 81, :hash "767610706"} {:id "defn/module-hash", :kind "defn", :line 83, :end-line 85, :hash "-1370811007"} {:id "defn/changed-form-indices", :kind "defn", :line 87, :end-line 98, :hash "-1681142834"} {:id "defn/build-embedded-manifest", :kind "defn", :line 100, :end-line 105, :hash "1204487047"} {:id "defn/embed-mutation-manifest", :kind "defn", :line 107, :end-line 121, :hash "1018794870"} {:id "defn-/backup-path", :kind "defn-", :line 123, :end-line 125, :hash "-1243914595"} {:id "defn/save-backup!", :kind "defn", :line 127, :end-line 129, :hash "1537045573"} {:id "defn/restore-from-backup!", :kind "defn", :line 131, :end-line 137, :hash "2000402189"} {:id "defn/cleanup-backup!", :kind "defn", :line 139, :end-line 143, :hash "293297155"} {:id "defn/now-str", :kind "defn", :line 145, :end-line 148, :hash "285237630"}]}
;; clj-mutate-manifest-end
