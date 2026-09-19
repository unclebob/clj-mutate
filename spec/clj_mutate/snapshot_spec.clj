(ns clj-mutate.snapshot-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.manifest :as manifest]
            [clj-mutate.snapshot :as snapshot]
            [clojure.java.io :as io]))

(describe "snapshot-path"
  (it "maps a src file to .metrics/mutate without the src prefix"
    (let [root (.getCanonicalPath (io/file "."))
          src (str root "/src/clj_mutate/geom.clj")]
      (should= (.getPath (io/file root ".metrics" "mutate" "clj_mutate" "geom.edn"))
               (snapshot/snapshot-path src root))))

  (it "uses the file name when the source is outside the project"
    (let [root (.getCanonicalPath (io/file "."))]
      (should= (.getPath (io/file root ".metrics" "mutate" "outside.edn"))
               (snapshot/snapshot-path "/tmp/outside.clj" root))))

  (it "maps a ClojureScript source file to the same snapshot layout"
    (let [root (.getCanonicalPath (io/file "."))
          src (str root "/src/myapp/browser/global_scores.cljs")]
      (should= (.getPath (io/file root ".metrics" "mutate" "myapp" "browser" "global_scores.edn"))
               (snapshot/snapshot-path src root)))))

(describe "merge-forms"
  (it "recounts killed/survived from outcomes on unchanged forms"
    (let [prior [{:id "defn/foo" :hash "a" :killed 4 :survived 1 :uncovered 0}]
          current [{:id "defn/foo" :hash "a" :kind "defn" :line 3}]
          outcomes {"defn/foo/1/arithmetic/plus-to-minus" :killed
                    "defn/foo/2/arithmetic/plus-to-minus" :survived}
          merged (snapshot/merge-forms prior current {} #{} outcomes)]
      (should= 1 (:killed (first merged)))
      (should= 1 (:survived (first merged)))
      (should= 0 (:uncovered (first merged)))))

  (it "takes uncovered from this run when the form was retested"
    (let [prior [{:id "defn/foo" :hash "a" :killed 4 :survived 1}]
          current [{:id "defn/foo" :hash "b" :kind "defn"}]
          stats {"defn/foo" {:killed 2 :survived 0 :uncovered 1}}
          outcomes {"defn/foo/1/arithmetic/plus-to-minus" :killed}
          merged (snapshot/merge-forms prior current stats #{"defn/foo"} outcomes)]
      (should= 1 (:killed (first merged)))
      (should= 0 (:survived (first merged)))
      (should= 1 (:uncovered (first merged)))))

  (it "does not carry counts across a rename (new id)"
    (let [prior [{:id "defn/old" :hash "a" :killed 9 :survived 0}]
          current [{:id "defn/new" :hash "a" :kind "defn"}]
          merged (snapshot/merge-forms prior current {} #{} {})]
      (should= 0 (:killed (first merged)))
      (should= "defn/new" (:id (first merged)))))

  (it "keeps prior counts on an unchanged form when the retry set is empty"
    (let [prior [{:id "defn/foo" :hash "a" :killed 4 :survived 1 :uncovered 0}]
          current [{:id "defn/foo" :hash "a" :kind "defn"}]
          merged (snapshot/merge-forms prior current {} #{} {})]
      (should= 4 (:killed (first merged)))
      (should= 1 (:survived (first merged)))
      (should= 0 (:uncovered (first merged)))))

  (it "records operator count per form"
    (let [current [{:id "defn/foo" :hash "a"}]
          sites [{:form-id "defn/foo"} {:form-id "defn/foo"}]
          merged (snapshot/merge-forms [] current {} #{} {} sites)]
      (should= 2 (:sites (first merged))))))

(describe "stats-by-form-id"
  (it "counts killed, survived, and uncovered per form"
    (let [stats (snapshot/stats-by-form-id
                  [{:site {:form-id "defn/foo"} :result :killed}
                   {:site {:form-id "defn/foo"} :result :survived}
                   {:site {:form-id "defn/bar"} :result :killed}]
                  [{:form-id "defn/foo"}])]
      (should= {:killed 1 :survived 1 :uncovered 1} (stats "defn/foo"))
      (should= {:killed 1} (stats "defn/bar")))))

(describe "sites-to-retry"
  (it "skips killed mutants on an unchanged form"
    (let [source "(ns demo)\n(defn foo [] (+ 1 2))\n"
          forms (manifest/top-level-form-manifest source)
          sites [{:form-id "defn/foo" :mutation-id "defn/foo/1/x" :form-index 1}
                 {:form-id "defn/foo" :mutation-id "defn/foo/2/x" :form-index 1}]
          prior {:module-hash "h"
                 :forms forms
                 :outcomes {"defn/foo/1/x" :killed
                            "defn/foo/2/x" :survived}}]
      (should= ["defn/foo/2/x"]
               (mapv :mutation-id (snapshot/sites-to-retry sites prior source)))))

  (it "retests every site on a rewritten form"
    (let [source "(ns demo)\n(defn foo [] (+ 1 20))\n"
          old-forms (manifest/top-level-form-manifest "(ns demo)\n(defn foo [] (+ 1 2))\n")
          sites [{:form-id "defn/foo" :mutation-id "defn/foo/1/x" :form-index 1}]
          prior {:module-hash "old"
                 :forms old-forms
                 :outcomes {"defn/foo/1/x" :killed}}]
      (should= sites (snapshot/sites-to-retry sites prior source)))))

(describe "merge-outcomes"
  (it "keeps unchanged-form outcomes and overwrites retested ids"
    (let [source "(ns demo)\n(defn foo [] (+ 1 2))\n"
          forms (manifest/top-level-form-manifest source)
          prior-outcomes {"defn/foo/1/x" :killed "defn/foo/2/x" :survived}
          results [{:site {:mutation-id "defn/foo/2/x"} :result :killed}]
          merged (snapshot/merge-outcomes prior-outcomes results forms forms)]
      (should= :killed (merged "defn/foo/1/x"))
      (should= :killed (merged "defn/foo/2/x")))))

(describe "load-prior"
  (it "falls back to an embedded footer when no snapshot file exists"
    (let [root (.getCanonicalPath (io/file "."))
          source (io/file root "target" "snap-load-demo.clj")
          footer {:version 2 :forms [{:id "defn/x" :hash "h"}]}
          content (manifest/embed-mutation-manifest "(ns demo)\n" footer)]
      (.mkdirs (.getParentFile source))
      (try
        (should= footer
                 (snapshot/load-prior (.getPath source) content root))
        (finally
          (io/delete-file source true))))))
