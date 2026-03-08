(ns clj-mutate.project
  (:import [java.io File]))

(defn bb-project?
  "True if the current working directory has a bb.edn file."
  ([] (bb-project? (System/getProperty "user.dir")))
  ([dir] (.exists (File. (str dir "/bb.edn")))))

(defn spec-command
  "Return the command vector for running specs."
  ([] (spec-command (System/getProperty "user.dir")))
  ([dir]
   (if (bb-project? dir)
     ["bb" "spec"]
     ["clj" "-M:spec"])))

(defn config-file
  "Return the project config filename (bb.edn or deps.edn)."
  ([] (config-file (System/getProperty "user.dir")))
  ([dir]
   (if (bb-project? dir)
     "bb.edn"
     "deps.edn")))
