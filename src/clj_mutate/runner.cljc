(ns clj-mutate.runner
  (:require [clojure.string :as str]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types])
  (:import [java.io File]
           [java.util.concurrent TimeUnit]))

(defn source->spec-path
  "Convert source path to spec path.
   src/empire/foo.cljc -> spec/empire/foo_spec.clj"
  [source-path]
  (-> source-path
      (str/replace #"^src/" "spec/")
      (str/replace #"\.cljc$" "_spec.clj")))

(defn spec-exists?
  "True if the spec file exists on disk."
  [spec-path]
  (.exists (File. spec-path)))

(defn run-spec
  "Run a spec file via clj -M:spec. Returns :killed, :survived, or :timeout.
   Optional timeout-ms: kill process after this many milliseconds.
   A timeout indicates an infinite loop — treated as :killed by caller."
  ([spec-path] (run-spec spec-path nil))
  ([spec-path timeout-ms]
   (let [pb (doto (ProcessBuilder. ^java.util.List ["clj" "-M:spec" spec-path])
              (.redirectErrorStream true))
         process (.start pb)
         _ (doto (Thread. (fn [] (try (let [is (.getInputStream process)]
                                        (while (not= -1 (.read is))))
                                      (catch Exception _))))
             (.setDaemon true)
             (.start))
         finished? (if timeout-ms
                     (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)
                     (do (.waitFor process) true))]
     (if finished?
       (if (zero? (.exitValue process))
         :survived
         :killed)
       (do (.destroyForcibly process)
           :timeout)))))

(defn run-spec-timed
  "Run spec without timeout. Returns {:result :killed/:survived :elapsed-ms N}."
  [spec-path]
  (let [start (System/currentTimeMillis)
        result (run-spec spec-path)
        elapsed (- (System/currentTimeMillis) start)]
    {:result result :elapsed-ms elapsed}))

(defn run-specs
  "Run multiple spec files. Returns :killed on first failure, :survived if all pass.
   Short-circuits: stops running specs as soon as one kills the mutant."
  [spec-paths timeout-ms]
  (loop [[path & more] spec-paths]
    (if (nil? path)
      :survived
      (let [result (run-spec path timeout-ms)]
        (if (= :survived result)
          (recur more)
          result)))))

(defn run-specs-timed
  "Run all specs without timeout. Returns {:result :killed/:survived :elapsed-ms N}."
  [spec-paths]
  (let [start (System/currentTimeMillis)
        result (run-specs spec-paths nil)
        elapsed (- (System/currentTimeMillis) start)]
    {:result result :elapsed-ms elapsed}))

(defn source-path->namespace
  "Convert source file path to Clojure namespace symbol.
   src/empire/map_utils.cljc -> empire.map-utils"
  [source-path]
  (-> source-path
      (str/replace #"^src/" "")
      (str/replace #"\.\w+$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")
      symbol))

(defn- spec-files
  "Recursively find all .clj files under dir."
  [dir]
  (->> (file-seq (File. dir))
       (filter #(str/ends-with? (.getName %) ".clj"))
       (map #(.getPath %))
       sort))

(defn extract-required-namespaces
  "Parse a source string's ns form and return set of required namespace symbols."
  [source-str]
  (let [rdr (reader-types/source-logging-push-back-reader source-str)
        opts {:read-cond :allow :features #{:clj} :eof ::eof}
        form (reader/read opts rdr)]
    (if (and (seq? form) (= 'ns (first form)))
      (let [require-clause (some #(and (seq? %) (= :require (first %)) %)
                                 form)]
        (if require-clause
          (set (map #(if (vector? %) (first %) %)
                    (rest require-clause)))
          #{}))
      #{})))

(defn find-specs-for-namespace
  "Find all spec files under spec-dir that require the given namespace."
  [target-ns spec-dir]
  (vec (filter
         (fn [path]
           (try
             (contains? (extract-required-namespaces (slurp path)) target-ns)
             (catch Exception _ false)))
         (spec-files spec-dir))))
