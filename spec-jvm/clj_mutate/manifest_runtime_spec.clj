(ns clj-mutate.manifest-runtime-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.workflow :as workflow]
            [clj-mutate.manifest :as manifest]
            [clj-mutate.project :as project]
            [clojure.edn :as edn]
            [clojure.java.shell :as shell]))

(describe "manifest runtime portability"
  (it "produces the same source digest under JVM Clojure and Babashka"
    (let [source "(ns portable)\n;; comment\n(defn f [xs] (map #(+ % 1) xs))\n"
          temp (java.io.File/createTempFile "clj-mutate-portable" ".cljc")]
      (try
        (spit temp source)
        (let [code (format
                     "(require '[clj-mutate.manifest :as m]) (print (m/module-hash (slurp %s)))"
                     (pr-str (.getPath temp)))
              {:keys [exit out err]} (shell/sh "bb" "-e" code)]
          (should= 0 exit)
          (should= "" err)
          (should= (manifest/module-hash source) out))
        (finally (.delete temp)))))

  (it "keeps source comparison portable but rejects another runtime's test verification"
    (let [source "(ns portable-profile)\n(defn f [] (+ 1 2))\n"
          provenance (workflow/mutation-provenance (project/default-test-command))
          embedded (manifest/embed-mutation-manifest
                     source
                     (manifest/build-embedded-manifest
                       source "2026-09-03T10:00:00-05:00"
                       {:verified? true :provenance provenance}))
          temp (java.io.File/createTempFile "clj-mutate-profile-portable" ".cljc")]
      (try
        (spit temp embedded)
        (let [code (format
                     (str "(require '[clj-mutate.manifest :as m] "
                          "'[clj-mutate.project :as p] '[clj-mutate.workflow :as w]) "
                          "(let [content (slurp %s) prior (m/extract-embedded-manifest content) "
                          "source (m/strip-mutation-metadata content) "
                          "provenance (w/mutation-provenance (p/default-test-command))] "
                          "(prn {:same-module? (= (:module-hash prior) (m/module-hash source)) "
                          ":trusted? (m/trusted-manifest? prior provenance) "
                          ":changed-forms (m/changed-form-indices source prior)}))")
                     (pr-str (.getPath temp)))
              {:keys [exit out err]} (shell/sh "bb" "-e" code)
              result (edn/read-string out)]
          (should= 0 exit)
          (should= "" err)
          (should= true (:same-module? result))
          (should= false (:trusted? result))
          (should= #{} (:changed-forms result)))
        (finally (.delete temp))))))

(run-specs)
