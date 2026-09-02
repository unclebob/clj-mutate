(ns clj-mutate.properties-spec
  (:require [speclj.core :refer :all]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clj-mutate.cli :as cli]
            [clj-mutate.lcov :as lcov]
            [clj-mutate.manifest :as manifest]
            [clj-mutate.mutations :as mutations]
            [clj-mutate.selection :as selection]
            [clj-mutate.source :as source]))

(defn- check
  [property]
  (let [result (tc/quick-check 100 property)]
    (when-not (:pass? result)
      (println result))
    (should (:pass? result))))

(def gen-site
  (gen/hash-map
    :line (gen/one-of [(gen/return nil) (gen/choose 1 20)])
    :original (gen/elements ['+ '- '> 0 1])
    :form-index (gen/choose 0 5)))

(def gen-form-entry
  (gen/hash-map
    :id (gen/fmap #(str "defn/f" %) (gen/choose 0 20))
    :kind (gen/return "defn")
    :line (gen/choose 1 50)
    :end-line (gen/choose 1 50)
    :hash (gen/fmap str (gen/choose 1 10000))))

(def gen-manifest
  (gen/hash-map
    :version (gen/return 1)
    :tested-at (gen/return "2026-01-01T00:00:00Z")
    :module-hash (gen/fmap str (gen/choose 1 10000))
    :forms (gen/vector gen-form-entry 0 6)))

(def gen-simple-form
  (gen/fmap
    (fn [[op a b]] (list op a b))
    (gen/tuple (gen/elements ['+ '- '> '< '=])
               (gen/elements [0 1])
               (gen/elements [0 1 2]))))

(describe "coverage partition properties"
  (tags :property :no-mutate)

  (it "covered and uncovered sites are a disjoint partition of all sites"
    (check
      (prop/for-all [sites (gen/vector gen-site 0 12)
                     covered-lines (gen/set (gen/choose 1 20))]
        (let [[covered uncovered] (source/partition-by-coverage sites covered-lines)]
          (and (= (count sites) (+ (count covered) (count uncovered)))
               (empty? (filter (set uncovered) covered))))))))

(describe "manifest properties"
  (tags :property :no-mutate)

  (it "embed then extract returns the same manifest"
    (check
      (prop/for-all [manifest gen-manifest]
        (let [content (manifest/embed-mutation-manifest "(ns foo)\n" manifest)]
          (= manifest (manifest/extract-embedded-manifest content))))))

  (it "strip-mutation-metadata is idempotent"
    (check
      (prop/for-all [manifest gen-manifest]
        (let [content (manifest/embed-mutation-manifest "(ns foo)\n(defn bar [] 1)\n" manifest)
              stripped (manifest/strip-mutation-metadata content)]
          (= stripped (manifest/strip-mutation-metadata stripped)))))))

(describe "mutation walk properties"
  (tags :property :no-mutate)

  (it "find-mutations assigns unique contiguous indices"
    (check
      (prop/for-all [form gen-simple-form]
        (let [sites (mutations/find-mutations form)
              indices (map :index sites)]
          (= indices (range (count sites)))))))

  (it "apply-mutation at each index replaces that site's original token"
    (check
      (prop/for-all [form gen-simple-form]
        (let [sites (mutations/find-mutations form)]
          (every? (fn [site]
                    (let [mutated (mutations/apply-mutation form (:index site))]
                      (not= form mutated)))
                  sites))))))

(describe "lcov properties"
  (tags :property :no-mutate)

  (it "positive DA counts are covered and zero counts are not"
    (check
      (prop/for-all [line-hits (gen/vector (gen/tuple (gen/choose 1 40) (gen/choose 0 5)) 1 8)]
        (let [unique-by-line (into {} line-hits)
              body (apply str (for [[line hits] unique-by-line]
                                (str "DA:" line "," hits "\n")))
              parsed (lcov/parse-lcov (str "SF:src/foo.cljc\n" body "end_of_record\n"))
              covered (get parsed "src/foo.cljc")]
          (and (every? (fn [[line hits]]
                         (if (pos? hits)
                           (contains? covered line)
                           (not (contains? covered line))))
                       unique-by-line)
               (every? unique-by-line covered)))))))

(describe "cli properties"
  (tags :property :no-mutate)

  (it "any argument list containing --help reports help"
    (check
      (prop/for-all [prefix (gen/vector gen/string-alphanumeric 0 4)
                     suffix (gen/vector gen/string-alphanumeric 0 4)]
        (let [result (cli/validate-args (into [] (concat prefix ["--help"] suffix)))]
          (true? (:help result)))))))

(describe "selection properties"
  (tags :property :no-mutate)

  (it "default-since-last-run? is false when lines or mutate-all are set"
    (check
      (prop/for-all [has-lines gen/boolean
                     since gen/boolean
                     mutate-all gen/boolean
                     has-manifest gen/boolean]
        (let [lines (when has-lines #{3})
              prior (when has-manifest {:module-hash "x"})
              result (selection/default-since-last-run? lines since mutate-all prior)]
          (= result
             (and (nil? lines)
                  (not mutate-all)
                  (or since (some? prior)))))))))

(run-specs)
