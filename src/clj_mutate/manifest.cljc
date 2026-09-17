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
  (let [root (syntax/of-source (as-source source-or-forms))
        locations (syntax/top-level-locations root)
        ids (syntax/top-level-form-ids (mapv syntax/sexpr locations))]
    (mapv
      (fn [idx zloc]
        (let [form (syntax/sexpr zloc)]
          {:id (nth ids idx)
           :kind (str (or (syntax/form-kind form) :literal))
           :line (:line (syntax/position zloc))
           :end-line (syntax/end-line zloc)
           :hash (digest/sha-256 (syntax/source zloc))}))
      (range)
      locations)))

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
  ([source-or-forms date-str {:keys [outcomes]
                              :or {outcomes {}}}]
   (cond-> {:version current-version
            :hash-algorithm hash-algorithm
            :tested-at date-str
            :module-hash (module-hash source-or-forms)
            :forms (top-level-form-manifest source-or-forms)}
     (seq outcomes) (assoc :outcomes outcomes))))

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
