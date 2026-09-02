(ns clj-mutate.architecture-spec
  (:require [speclj.core :refer :all]
            [clojure.string :as str]
            [clj-mutate.source :as source]))

(def allowed-deps
  '{clj-mutate.mutations #{}
    clj-mutate.project #{}
    clj-mutate.lcov #{}
    clj-mutate.backup #{}
    clj-mutate.report #{}
    clj-mutate.manifest #{}
    clj-mutate.source #{clj-mutate.mutations}
    clj-mutate.cli #{clj-mutate.project}
    clj-mutate.coverage #{clj-mutate.lcov clj-mutate.project}
    clj-mutate.runner #{clj-mutate.project}
    clj-mutate.workers #{clj-mutate.project}
    clj-mutate.execution #{clj-mutate.report clj-mutate.runner clj-mutate.source clj-mutate.workers}
    clj-mutate.workflow #{clj-mutate.backup clj-mutate.coverage clj-mutate.execution
                          clj-mutate.manifest clj-mutate.project clj-mutate.report
                          clj-mutate.runner clj-mutate.source}
    clj-mutate.core #{clj-mutate.cli clj-mutate.workflow}})

(def far-from-io
  '#{clj-mutate.mutations clj-mutate.lcov clj-mutate.manifest clj-mutate.source clj-mutate.report})

(defn- source-files
  []
  (->> (file-seq (java.io.File. "src/clj_mutate"))
       (filter #(.isFile %))
       (filter #(re-find #"\.clj[cs]?$" (.getName %)))
       (sort-by #(.getPath %))))

(defn- required-libs
  [ns-form]
  (let [require-clause (->> ns-form
                            (filter seq?)
                            (filter #(= :require (first %)))
                            first)]
    (if-not require-clause
      #{}
      (->> (rest require-clause)
           (map (fn [spec]
                  (cond
                    (symbol? spec) spec
                    (vector? spec) (first spec)
                    :else nil)))
           (remove nil?)
           set))))

(describe "module dependencies"
  (it "only allow declared clj-mutate dependencies"
    (doseq [file (source-files)]
      (let [ns-form (first (source/read-source-forms (slurp file)))
            n (second ns-form)
            actual (->> (required-libs ns-form)
                        (filter #(str/starts-with? (str %) "clj-mutate."))
                        set)
            allowed (get allowed-deps n)]
        (should-not-be-nil allowed)
        (should= allowed actual))))

  (it "keeps high-level modules free of shell IO"
    (doseq [file (source-files)]
      (let [ns-form (first (source/read-source-forms (slurp file)))
            n (second ns-form)
            libs (required-libs ns-form)]
        (when (contains? far-from-io n)
          (should-not (contains? libs 'clojure.java.shell)))))))

(run-specs)
