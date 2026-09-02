(ns clj-mutate.project
  (:require [clojure.string :as str])
  (:import [java.io File]))

(defn bb-project?
  "True if the directory has a bb.edn file."
  ([] (bb-project? (System/getProperty "user.dir")))
  ([dir] (.exists (File. (str dir "/bb.edn")))))

(defn spec-command
  "Return the command vector for running specs."
  ([] (spec-command (System/getProperty "user.dir")))
  ([dir]
   (if (bb-project? dir)
     ["bb" "spec"]
     ["clj" "-M:spec" "--tag" "~no-mutate"])))

(defn default-test-command
  "Return the default shell command for running specs."
  ([] (default-test-command (System/getProperty "user.dir")))
  ([dir]
   (str/join " " (spec-command dir))))

(defn config-file
  "Return the project config filename (bb.edn or deps.edn)."
  ([] (config-file (System/getProperty "user.dir")))
  ([dir]
   (if (bb-project? dir)
     "bb.edn"
     "deps.edn")))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-09-02T15:14:55.19652-05:00", :module-hash "968523290", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line nil, :hash "-827077084"} {:id "defn/bb-project?", :kind "defn", :line 5, :end-line nil, :hash "445274108"} {:id "defn/spec-command", :kind "defn", :line 10, :end-line nil, :hash "611947785"} {:id "defn/default-test-command", :kind "defn", :line 18, :end-line nil, :hash "955996130"} {:id "defn/config-file", :kind "defn", :line 24, :end-line nil, :hash "1511276396"}]}
;; clj-mutate-manifest-end
