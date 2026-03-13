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

(declare extract-embedded-manifest
         changed-form-indices-by-reason)

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
  (let [{:keys [changed-form-indices]} (changed-form-indices-by-reason forms manifest)]
    changed-form-indices))

(defn changed-form-indices-by-reason
  [forms manifest]
  (let [current (top-level-form-manifest forms)
        previous-by-id (into {} (map (juxt :id identity) (:forms manifest)))
        new-form-indices
        (->> current
             (keep-indexed
               (fn [idx form-entry]
                 (when (nil? (get previous-by-id (:id form-entry)))
                   idx)))
             set)
        manifest-violating-form-indices
        (->> current
             (keep-indexed
               (fn [idx form-entry]
                 (let [previous (get previous-by-id (:id form-entry))]
                   (when (and previous
                              (not= (:hash previous) (:hash form-entry)))
                     idx))))
             set)]
    {:new-form-indices new-form-indices
     :manifest-violating-form-indices manifest-violating-form-indices
     :changed-form-indices (into new-form-indices manifest-violating-form-indices)}))

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
;; {:version 1, :tested-at "2026-03-13T07:03:11.402603-05:00", :module-hash "347620054", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line 4, :hash "-1861788327"} {:id "def/mutation-comment-re", :kind "def", :line 6, :end-line 6, :hash "739874186"} {:id "def/manifest-start-line", :kind "def", :line 7, :end-line 7, :hash "-1825565512"} {:id "def/manifest-end-line", :kind "def", :line 8, :end-line 8, :hash "744098285"} {:id "def/manifest-block-re", :kind "def", :line 9, :end-line 15, :hash "-1957750279"} {:id "form/5/declare", :kind "declare", :line 17, :end-line 18, :hash "803429075"} {:id "defn/extract-mutation-date", :kind "defn", :line 20, :end-line 25, :hash "-569806568"} {:id "defn/stamp-mutation-date", :kind "defn", :line 27, :end-line 32, :hash "-1761741461"} {:id "defn/extract-embedded-manifest", :kind "defn", :line 34, :end-line 40, :hash "815988834"} {:id "defn/strip-embedded-manifest", :kind "defn", :line 42, :end-line 44, :hash "1175673264"} {:id "defn/strip-mutation-metadata", :kind "defn", :line 46, :end-line 50, :hash "1841423544"} {:id "defn-/form-kind", :kind "defn-", :line 52, :end-line 55, :hash "184035403"} {:id "defn-/top-level-form-id", :kind "defn-", :line 57, :end-line 70, :hash "317205413"} {:id "defn/top-level-form-manifest", :kind "defn", :line 72, :end-line 82, :hash "767610706"} {:id "defn/module-hash", :kind "defn", :line 84, :end-line 86, :hash "-1370811007"} {:id "defn/changed-form-indices", :kind "defn", :line 88, :end-line 91, :hash "1255192901"} {:id "defn/changed-form-indices-by-reason", :kind "defn", :line 93, :end-line 115, :hash "-30389076"} {:id "defn/build-embedded-manifest", :kind "defn", :line 117, :end-line 122, :hash "1204487047"} {:id "defn/embed-mutation-manifest", :kind "defn", :line 124, :end-line 138, :hash "-877975997"} {:id "defn-/backup-path", :kind "defn-", :line 140, :end-line 142, :hash "-1243914595"} {:id "defn/save-backup!", :kind "defn", :line 144, :end-line 146, :hash "1537045573"} {:id "defn/restore-from-backup!", :kind "defn", :line 148, :end-line 154, :hash "2000402189"} {:id "defn/cleanup-backup!", :kind "defn", :line 156, :end-line 160, :hash "293297155"} {:id "defn/now-str", :kind "defn", :line 162, :end-line 165, :hash "285237630"}]}
;; clj-mutate-manifest-end
