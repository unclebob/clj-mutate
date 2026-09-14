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
               (snapshot/snapshot-path "/tmp/outside.clj" root)))))

(describe "merge-forms"
  (it "keeps killed/survived when id and hash are unchanged"
    (let [prior [{:id "defn/foo" :hash "a" :killed 4 :survived 1 :uncovered 0}]
          current [{:id "defn/foo" :hash "a" :kind "defn" :line 3}]
          merged (snapshot/merge-forms prior current {} #{})]
      (should= 4 (:killed (first merged)))
      (should= 1 (:survived (first merged)))))

  (it "replaces counts for a form that was retested"
    (let [prior [{:id "defn/foo" :hash "a" :killed 4 :survived 1}]
          current [{:id "defn/foo" :hash "b" :kind "defn"}]
          stats {"defn/foo" {:killed 2 :survived 0 :uncovered 1}}
          merged (snapshot/merge-forms prior current stats #{"defn/foo"})]
      (should= 2 (:killed (first merged)))
      (should= 0 (:survived (first merged)))
      (should= 1 (:uncovered (first merged)))))

  (it "does not carry counts across a rename (new id)"
    (let [prior [{:id "defn/old" :hash "a" :killed 9 :survived 0}]
          current [{:id "defn/new" :hash "a" :kind "defn"}]
          merged (snapshot/merge-forms prior current {} #{})]
      (should= 0 (:killed (first merged)))
      (should= "defn/new" (:id (first merged))))))

(describe "stats-by-form-id"
  (it "counts killed, survived, and uncovered per form"
    (let [stats (snapshot/stats-by-form-id
                  [{:site {:form-id "defn/foo"} :result :killed}
                   {:site {:form-id "defn/foo"} :result :survived}
                   {:site {:form-id "defn/bar"} :result :killed}]
                  [{:form-id "defn/foo"}])]
      (should= {:killed 1 :survived 1 :uncovered 1} (stats "defn/foo"))
      (should= {:killed 1} (stats "defn/bar")))))

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
