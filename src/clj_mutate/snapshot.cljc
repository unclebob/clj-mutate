(ns clj-mutate.snapshot
  (:require [clj-mutate.manifest :as manifest]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def metrics-root ".metrics")

(defn- canonical-file [path]
  (.getCanonicalFile (io/file path)))

(defn snapshot-path
  "`.metrics/mutate/<path-under-src>.edn` for a source file."
  ([source-path]
   (snapshot-path source-path (System/getProperty "user.dir")))
  ([source-path root]
   (let [root-file (canonical-file root)
         src-file (canonical-file source-path)
         rel (try
               (str (.relativize (.toPath root-file) (.toPath src-file)))
               (catch Exception _
                 (.getName src-file)))
         rel (str/replace rel #"\\" "/")
         rel (if (str/starts-with? rel "..")
               (.getName src-file)
               rel)
         rel (str/replace rel #"^src/" "")
         rel (str/replace rel #"\.[^.]+$" ".edn")]
     (.getPath (io/file root metrics-root "mutate" rel)))))

(defn read-snapshot
  ([source-path]
   (read-snapshot source-path (System/getProperty "user.dir")))
  ([source-path root]
   (let [f (io/file (snapshot-path source-path root))]
     (when (.exists f)
       (edn/read-string (slurp f))))))

(defn load-prior
  "Snapshot file, or a source footer if no snapshot exists yet."
  ([source-path content]
   (load-prior source-path content (System/getProperty "user.dir")))
  ([source-path content root]
   (or (read-snapshot source-path root)
       (manifest/extract-embedded-manifest content))))

(defn write-snapshot!
  ([source-path snapshot]
   (write-snapshot! source-path snapshot (System/getProperty "user.dir")))
  ([source-path snapshot root]
   (let [f (io/file (snapshot-path source-path root))]
     (io/make-parents f)
     (spit f (str (pr-str snapshot) "\n"))
     (.getPath f))))

(defn strip-source-footer!
  [source-path]
  (let [content (slurp source-path)
        stripped (manifest/strip-mutation-metadata content)]
    (when (not= content stripped)
      (spit source-path stripped))
    stripped))

(defn usable-snapshot?
  "True when `m` has a module hash and form list we can compare."
  [m]
  (and (map? m)
       (string? (:module-hash m))
       (vector? (:forms m))))

(defn- form-prefix [form-id]
  (str form-id "/"))

(defn- outcome-for-form? [form-id mutation-id]
  (str/starts-with? (str mutation-id) (form-prefix form-id)))

(defn- unchanged-form-ids [current-forms prior-forms]
  (let [previous-by-id (into {} (map (juxt :id identity) (or prior-forms [])))]
    (into #{}
          (keep (fn [f]
                  (when-let [p (get previous-by-id (:id f))]
                    (when (= (:hash p) (:hash f))
                      (:id f))))
                current-forms))))

(defn- counts-from-outcomes [form-id outcomes]
  (let [xs (filter (fn [[k _]] (outcome-for-form? form-id k)) outcomes)]
    {:killed (count (filter (fn [[_ r]] (= r :killed)) xs))
     :survived (count (filter (fn [[_ r]] (= r :survived)) xs))}))

(defn merge-outcomes
  "Keep outcomes on unchanged forms. This run overwrites ids it retested.
   Rewritten or renamed forms drop their old outcomes."
  [prior-outcomes results current-forms prior-forms]
  (let [unchanged (unchanged-form-ids current-forms prior-forms)
        kept (into {}
                   (filter (fn [[mid _]]
                             (some #(outcome-for-form? % mid) unchanged))
                           (or prior-outcomes {})))
        from-run (into {}
                       (keep (fn [r]
                               (when-let [id (get-in r [:site :mutation-id])]
                                 [id (:result r)]))
                             results))]
    (merge kept from-run)))

(defn stats-by-form-id
  [results uncovered]
  (let [acc (reduce (fn [m r]
                      (let [id (get-in r [:site :form-id])
                            k (if (= :killed (:result r)) :killed :survived)]
                        (if id
                          (update-in m [id k] (fnil inc 0))
                          m)))
                    {}
                    results)]
    (reduce (fn [m site]
              (let [id (:form-id site)]
                (if id
                  (update-in m [id :uncovered] (fnil inc 0))
                  m)))
            acc
            uncovered)))

(defn merge-forms
  "Recount killed/survived from outcomes. Unchanged forms keep uncovered."
  [prior-forms current-forms stats-by-id tested-ids outcomes]
  (let [prior-by-key (into {}
                           (map (fn [f] [[(:id f) (:hash f)] f])
                                (or prior-forms [])))]
    (mapv (fn [f]
            (let [prev (get prior-by-key [(:id f) (:hash f)])
                  run (get stats-by-id (:id f))
                  oc (counts-from-outcomes (:id f) outcomes)
                  uncovered (cond
                              (contains? tested-ids (:id f))
                              (or (:uncovered run) 0)
                              prev (or (:uncovered prev) 0)
                              :else 0)]
              (assoc f
                :killed (:killed oc)
                :survived (:survived oc)
                :uncovered uncovered)))
          current-forms)))

(defn sites-to-retry
  "Survivors on unchanged forms, plus every site on new or rewritten forms.
   Previously killed mutants on unchanged forms are skipped."
  [sites prior source]
  (if-not (usable-snapshot? prior)
    (vec sites)
    (let [current (manifest/top-level-form-manifest source)
          previous-by-id (into {} (map (juxt :id identity) (:forms prior)))
          outcomes (or (:outcomes prior) {})
          unchanged (unchanged-form-ids current (:forms prior))]
      (vec
        (filter
          (fn [site]
            (let [fid (:form-id site)
                  mid (:mutation-id site)]
              (if (contains? unchanged fid)
                (let [o (get outcomes mid)]
                  (cond
                    (= :killed o) false
                    (= :survived o) true
                    :else (pos? (or (:survived (get previous-by-id fid)) 0))))
                true)))
          sites)))))

(defn build-snapshot
  [source-path analysis-content date-str
   {:keys [prior-forms prior-outcomes results uncovered tested-ids]
    :or {prior-forms [] prior-outcomes {} results [] uncovered [] tested-ids #{}}}]
  (let [current (manifest/top-level-form-manifest analysis-content)
        stats (stats-by-form-id results uncovered)
        outcomes (merge-outcomes prior-outcomes results current prior-forms)
        forms (merge-forms prior-forms current stats tested-ids outcomes)]
    {:version manifest/current-version
     :hash-algorithm manifest/hash-algorithm
     :tested-at date-str
     :source source-path
     :module-hash (manifest/module-hash analysis-content)
     :outcomes outcomes
     :forms forms}))
