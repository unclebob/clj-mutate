(ns clj-mutate.manifest
  (:require [clj-mutate.digest :as digest]
            [clj-mutate.syntax :as syntax]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def current-version 2)
(def hash-algorithm :sha256-source-v1)

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

(defn- as-source
  [source-or-forms]
  (if (string? source-or-forms)
    (syntax/normalize-newlines source-or-forms)
    (str/join "\n" (map pr-str source-or-forms))))

(defn top-level-form-manifest
  [source-or-forms]
  (let [root (syntax/of-source (as-source source-or-forms))]
    (mapv
      (fn [idx zloc]
        (let [form (syntax/sexpr zloc)]
          {:id (syntax/top-level-form-id idx form)
           :kind (str (or (syntax/form-kind form) :literal))
           :line (:line (syntax/position zloc))
           :end-line (syntax/end-line zloc)
           :hash (digest/sha-256 (syntax/source zloc))}))
      (range)
      (syntax/top-level-locations root))))

(defn module-hash
  [source-or-forms]
  (->> (top-level-form-manifest source-or-forms)
       (map :hash)
       (str/join "\u0000")
       digest/sha-256))

(defn current-manifest?
  [manifest]
  (and (= current-version (:version manifest))
       (= hash-algorithm (:hash-algorithm manifest))))

(defn trusted-manifest?
  [manifest provenance]
  (and (current-manifest? manifest)
       (true? (:verified? manifest))
       (string? (:tested-at manifest))
       (string? (:module-hash manifest))
       (vector? (:forms manifest))
       (= provenance (:provenance manifest))))

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
  ([source-or-forms date-str]
   (build-embedded-manifest source-or-forms date-str {}))
  ([source-or-forms date-str {:keys [verified? provenance]
                              :or {verified? true provenance {}}}]
   {:version current-version
    :hash-algorithm hash-algorithm
    :verified? verified?
    :tested-at date-str
    :module-hash (module-hash source-or-forms)
    :provenance provenance
    :forms (top-level-form-manifest source-or-forms)}))

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

(defn now-str
  []
  (.format (java.time.OffsetDateTime/now)
           java.time.format.DateTimeFormatter/ISO_OFFSET_DATE_TIME))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-09-02T15:17:41.568678-05:00", :module-hash "-1343313178", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line nil, :hash "153964126"} {:id "def/mutation-comment-re", :kind "def", :line 5, :end-line nil, :hash "739874186"} {:id "def/manifest-start-line", :kind "def", :line 6, :end-line nil, :hash "-1825565512"} {:id "def/manifest-end-line", :kind "def", :line 7, :end-line nil, :hash "744098285"} {:id "def/manifest-block-re", :kind "def", :line 8, :end-line nil, :hash "-1957750279"} {:id "form/5/declare", :kind "declare", :line 16, :end-line nil, :hash "803429075"} {:id "defn/extract-mutation-date", :kind "defn", :line 19, :end-line nil, :hash "-569806568"} {:id "defn/stamp-mutation-date", :kind "defn", :line 26, :end-line nil, :hash "-1761741461"} {:id "defn/extract-embedded-manifest", :kind "defn", :line 33, :end-line nil, :hash "1043997453"} {:id "defn/strip-embedded-manifest", :kind "defn", :line 41, :end-line nil, :hash "1175673264"} {:id "defn/strip-mutation-metadata", :kind "defn", :line 45, :end-line nil, :hash "1841423544"} {:id "defn-/form-kind", :kind "defn-", :line 51, :end-line nil, :hash "184035403"} {:id "defn-/top-level-form-id", :kind "defn-", :line 56, :end-line nil, :hash "317205413"} {:id "defn/top-level-form-manifest", :kind "defn", :line 71, :end-line nil, :hash "767610706"} {:id "defn/module-hash", :kind "defn", :line 83, :end-line nil, :hash "-1370811007"} {:id "defn/changed-form-indices", :kind "defn", :line 87, :end-line nil, :hash "1255192901"} {:id "defn/changed-form-indices-by-reason", :kind "defn", :line 92, :end-line nil, :hash "-30389076"} {:id "defn/build-embedded-manifest", :kind "defn", :line 116, :end-line nil, :hash "1204487047"} {:id "defn/embed-mutation-manifest", :kind "defn", :line 123, :end-line nil, :hash "1197463130"} {:id "defn/now-str", :kind "defn", :line 139, :end-line nil, :hash "285237630"}]}
;; clj-mutate-manifest-end
