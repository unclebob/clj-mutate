(ns clj-mutate.project
  (:require [clj-mutate.digest :as digest]
            [clojure.string :as str])
  (:import [java.io File]))

(defn running-on-babashka?
  "True when the current process is Babashka."
  []
  (some? (System/getProperty "babashka.version")))

(defn bb-project?
  "True if the directory has a bb.edn file."
  ([] (bb-project? (System/getProperty "user.dir")))
  ([dir] (.exists (File. (str dir "/bb.edn")))))

(defn spec-command
  "Return the command vector for running specs.
   Uses the current runtime, not the presence of bb.edn, so a JVM
   launch in a dual-config project still runs clj -M:spec."
  ([] (spec-command (System/getProperty "user.dir")))
  ([_dir]
   (if (running-on-babashka?)
     ["bb" "spec" "--tag" "~no-mutate"]
     ["clj" "-M:spec" "--tag" "~no-mutate"])))

(defn default-test-command
  "Return the default shell command for running specs."
  ([] (default-test-command (System/getProperty "user.dir")))
  ([dir]
   (str/join " " (spec-command dir))))

(defn default-coverage-command
  "Return the default command used to generate LCOV. Babashka projects must
   opt in because they may not have the Clojure CLI available."
  []
  (when-not (running-on-babashka?)
    "clj -M:cov --lcov"))

(defn test-directories
  "Return the conventional test roots for the current runtime."
  ([] (test-directories (System/getProperty "user.dir")))
  ([dir]
   (->> (if (running-on-babashka?)
          ["spec" "spec-bb"]
          ["spec" "spec-jvm"])
        (filter #(-> (File. (str dir "/" %)) .isDirectory))
        vec)))

(defn- source-file?
  [^File file]
  (and (.isFile file)
       (some #(str/ends-with? (.getName file) %)
             [".clj" ".cljc" ".cljs" ".edn"])))

(defn test-profile-fingerprint
  "Fingerprint the effective command and conventional runtime-specific test
   roots. Unrelated development aliases in deps.edn are intentionally ignored."
  ([test-command]
   (test-profile-fingerprint (System/getProperty "user.dir") test-command))
  ([dir test-command]
   (let [root (File. dir)
         entries (->> (test-directories dir)
                      (mapcat #(file-seq (File. root %)))
                      (filter source-file?)
                      (map (fn [^File file]
                             [(.toString (.relativize (.toPath root) (.toPath file)))
                              (digest/sha-256 (slurp file))]))
                      (sort-by first)
                      vec)]
     (digest/sha-256 (pr-str {:test-command test-command
                              :files entries})))))

(defn config-file
  "Return the project config filename (bb.edn or deps.edn)."
  ([] (config-file (System/getProperty "user.dir")))
  ([dir]
   (if (bb-project? dir)
     "bb.edn"
     "deps.edn")))

(defn config-files
  "Return every project config file present in dir."
  ([] (config-files (System/getProperty "user.dir")))
  ([dir]
   (let [present (filterv #(.exists (File. (str dir "/" %))) ["bb.edn" "deps.edn"])]
     (if (seq present)
       present
       [(config-file dir)]))))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-09-02T15:14:55.19652-05:00", :module-hash "968523290", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line nil, :hash "-827077084"} {:id "defn/bb-project?", :kind "defn", :line 5, :end-line nil, :hash "445274108"} {:id "defn/spec-command", :kind "defn", :line 10, :end-line nil, :hash "611947785"} {:id "defn/default-test-command", :kind "defn", :line 18, :end-line nil, :hash "955996130"} {:id "defn/config-file", :kind "defn", :line 24, :end-line nil, :hash "1511276396"}]}
;; clj-mutate-manifest-end
