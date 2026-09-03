(ns clj-mutate.manifest-runtime-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.manifest :as manifest]
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
        (finally (.delete temp))))))

(run-specs)
