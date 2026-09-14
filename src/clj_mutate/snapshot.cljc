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
  "Keep counts for unchanged id+hash. Retested forms take this run.
   Rename/move (new id or hash) starts at this run or zeros."
  [prior-forms current-forms stats-by-id tested-ids]
  (let [prior-by-key (into {}
                           (map (fn [f] [[(:id f) (:hash f)] f])
                                (or prior-forms [])))]
    (mapv (fn [f]
            (let [prev (get prior-by-key [(:id f) (:hash f)])
                  run (get stats-by-id (:id f))
                  tested? (contains? tested-ids (:id f))]
              (cond
                tested?
                (assoc f
                  :killed (or (:killed run) 0)
                  :survived (or (:survived run) 0)
                  :uncovered (or (:uncovered run) 0))
                prev
                (merge f (select-keys prev [:killed :survived :uncovered]))
                :else
                (assoc f :killed 0 :survived 0 :uncovered 0))))
          current-forms)))

(defn build-snapshot
  [source-path analysis-content date-str
   {:keys [verified? provenance prior-forms results uncovered tested-ids]
    :or {verified? false provenance {} results [] uncovered [] tested-ids #{}}}]
  (let [current (manifest/top-level-form-manifest analysis-content)
        stats (stats-by-form-id results uncovered)
        forms (merge-forms prior-forms current stats tested-ids)]
    {:version manifest/current-version
     :hash-algorithm manifest/hash-algorithm
     :verified? (boolean verified?)
     :tested-at date-str
     :source source-path
     :module-hash (manifest/module-hash analysis-content)
     :provenance provenance
     :forms forms}))
