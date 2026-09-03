(ns clj-mutate.project
  (:require [clj-mutate.digest :as digest]
            [clojure.edn :as edn]
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

(defn- source-file?
  [^File file]
  (and (.isFile file)
       (some #(str/ends-with? (.getName file) %)
             [".clj" ".cljc" ".cljs" ".edn"])))

(defn- contains-source-files?
  [^File dir]
  (boolean (some source-file? (file-seq dir))))

(defn test-directories
  "Return the conventional test roots for the current runtime that actually
   contain Clojure sources. Markdown-only spec/ notes are ignored; clojure.test
   projects can use test/."
  ([] (test-directories (System/getProperty "user.dir")))
  ([dir]
   (->> (if (running-on-babashka?)
          ["spec" "spec-bb" "test"]
          ["spec" "spec-jvm" "test"])
        (filter (fn [name]
                  (let [candidate (File. (str dir "/" name))]
                    (and (.isDirectory candidate)
                         (contains-source-files? candidate)))))
        vec)))

(defn- read-edn-file
  [^File file]
  (when (.isFile file)
    (try
      (edn/read-string (slurp file))
      (catch Exception _ nil))))

(defn- command-tokens
  [command]
  (->> (str/split (or command "") #"\s+")
       (remove str/blank?)
       vec))

(defn- clojure-aliases
  [command]
  (->> (command-tokens command)
       (mapcat (fn [token]
                 (when-let [[_ aliases] (re-matches #"-[MAXT](:.+)" token)]
                   (->> (str/split aliases #":")
                        (remove str/blank?)
                        (map keyword)))))
       vec))

(defn- bb-task
  [command]
  (let [[executable & args] (command-tokens command)]
    (when (= "bb" executable)
      (some #(when-not (str/starts-with? % "-") (keyword %)) args))))

(defn- selected-command-config
  [dir command]
  (let [deps (read-edn-file (File. dir "deps.edn"))
        bb (read-edn-file (File. dir "bb.edn"))
        aliases (clojure-aliases command)
        task (bb-task command)]
    (cond-> {}
      (seq aliases)
      (assoc :clojure {:paths (:paths deps)
                       :deps (:deps deps)
                       :mvn/repos (:mvn/repos deps)
                       :aliases (select-keys (:aliases deps) aliases)})

      task
      (assoc :babashka {:paths (:paths bb)
                        :deps (:deps bb)
                        :task (get-in bb [:tasks task])}))))

(defn- root-bearing-config
  [config]
  (cond-> {}
    (:clojure config)
    (assoc :clojure
           {:aliases
            (into {}
                  (map (fn [[alias details]]
                         [alias (select-keys details
                                             [:extra-paths :replace-paths])]))
                  (get-in config [:clojure :aliases]))})

    (:babashka config)
    (assoc :babashka
           (select-keys (:babashka config) [:task]))))

(defn- project-relative-directory
  [dir path]
  (let [project-root (.getCanonicalFile (File. dir))
        candidate (.getCanonicalFile (File. project-root path))
        root-path (.toPath project-root)
        candidate-path (.toPath candidate)]
    (when (and (.isDirectory candidate)
               (not= root-path candidate-path)
               (.startsWith candidate-path root-path))
      (.toString (.relativize root-path candidate-path)))))

(defn- existing-directory-strings
  [dir value]
  (->> (tree-seq coll? seq value)
       (filter string?)
       (map str/trim)
       (remove str/blank?)
       (keep #(project-relative-directory dir %))
       (remove #(or (= "src" %)
                    (str/starts-with? % "src/")))
       distinct
       sort
       vec))

(defn test-profile-roots
  "Return the test roots that form a command's effective test profile. Explicit
   roots are authoritative; otherwise roots are inferred from the selected
   deps.edn alias or bb.edn task, with runtime conventions as a fallback for
   the default command."
  ([test-command]
   (test-profile-roots (System/getProperty "user.dir") test-command nil))
  ([dir test-command]
   (test-profile-roots dir test-command nil))
  ([dir test-command explicit-roots]
   (let [roots (if (seq explicit-roots)
                 (keep #(project-relative-directory dir %) explicit-roots)
                 (let [inferred (existing-directory-strings
                                  dir (root-bearing-config
                                        (selected-command-config dir test-command)))]
                   (if (seq inferred)
                     inferred
                     (when (= test-command (default-test-command dir))
                       (test-directories dir)))))]
     (->> roots
          (map str)
          (map str/trim)
          (remove str/blank?)
          distinct
          sort
          vec))))

(defn commands-share-test-profile?
  "True when test and coverage commands are tied to the same effective test
   roots. Explicit roots fill in only when a command cannot infer a population;
   they do not paper over two commands that infer different roots."
  ([test-command coverage-command]
   (commands-share-test-profile? (System/getProperty "user.dir")
                                 test-command coverage-command nil))
  ([dir test-command coverage-command explicit-roots]
   (let [inferred-test (test-profile-roots dir test-command nil)
         inferred-coverage (test-profile-roots dir coverage-command nil)
         declared (when (seq explicit-roots)
                    (test-profile-roots dir nil explicit-roots))]
     (cond
       (and (seq inferred-test) (seq inferred-coverage))
       (= inferred-test inferred-coverage)

       (seq declared)
       (and (or (empty? inferred-test) (= inferred-test declared))
            (or (empty? inferred-coverage) (= inferred-coverage declared)))

       :else
       false))))

(defn namespace-scoped-test-command?
  "True when the command selects namespaces with -n or --namespace."
  [command]
  (boolean (some #{"-n" "--namespace"} (command-tokens command))))

(defn test-profile-fingerprint
  "Fingerprint the effective command and conventional runtime-specific test
   roots. Only the selected alias/task configuration is included, so unrelated
   development aliases in deps.edn remain intentionally ignored."
  ([test-command]
   (test-profile-fingerprint (System/getProperty "user.dir") test-command nil))
  ([dir test-command]
   (test-profile-fingerprint dir test-command nil))
  ([dir test-command explicit-roots]
   (let [root (File. dir)
         roots (test-profile-roots dir test-command explicit-roots)
         entries (->> roots
                      (mapcat #(file-seq (File. root %)))
                      (filter source-file?)
                      (map (fn [^File file]
                             [(.toString (.relativize (.toPath root) (.toPath file)))
                              (digest/sha-256 (slurp file))]))
                      (sort-by first)
                      vec)]
     (digest/sha-256 (pr-str {:test-command test-command
                              :test-roots roots
                              :selected-config (selected-command-config dir test-command)
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
