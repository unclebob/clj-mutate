(ns clj-mutate.core
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]
            [clj-mutate.coverage :as coverage]
            [clj-mutate.mutations :as mutations]
            [clj-mutate.runner :as runner]
            [clj-mutate.workers :as workers])
  (:import [java.io File]))

(def ^:private mutation-comment-re #"^;; mutation-tested: (\d{4}-\d{2}-\d{2})")
(def ^:private manifest-start-line ";; clj-mutate-manifest-begin")
(def ^:private manifest-end-line ";; clj-mutate-manifest-end")
(def ^:private manifest-block-re
  (re-pattern
    (str "(?ms)\n?"
         (java.util.regex.Pattern/quote manifest-start-line)
         "\n(.*?)\n"
         (java.util.regex.Pattern/quote manifest-end-line)
         "\n?$")))
(def ^:private worker-root-dir "target/mutation-workers")

(def ^:private usage-summary
  (str
    "Usage: clj -M:mutate <source-file.cljc> [options]\n"
    "\n"
    "Options:\n"
    "  --lines L1,L2,...      Run only mutations on these source lines\n"
    "  --since-last-run       Run only mutations in changed top-level forms since last successful run\n"
    "  --mutate-all           Run all covered mutations even if a manifest exists\n"
    "  --mutation-warning N   Warn when more than N mutations are found (default 50)\n"
    "  --timeout-factor N     Mutation timeout multiplier vs baseline (default 10)\n"
    "  --test-command CMD     Test command to run (default \"clj -M:spec --tag ~no-mutate\")\n"
    "  --max-workers N        Limit parallel workers to N (positive integer)\n"
    "  --help                 Print this help and exit\n"))

(declare extract-embedded-manifest)

(defn extract-mutation-date
  "Extract the mutation test date from a source file's content.
   Returns the date string or nil."
  [content]
  (or (when-let [manifest (:tested-at (extract-embedded-manifest content))]
        manifest)
      (when-let [m (re-find mutation-comment-re content)]
        (second m))))

(defn stamp-mutation-date
  "Add or replace the mutation-tested comment at the top of source content."
  [content date-str]
  (let [comment-line (str ";; mutation-tested: " date-str)]
    (if (re-find mutation-comment-re content)
      (str/replace content mutation-comment-re comment-line)
      (str comment-line "\n" content))))

(declare build-embedded-manifest)

(defn extract-embedded-manifest
  "Read the embedded mutation manifest from the end of the file, if present."
  [content]
  (when-let [[_ raw-body] (re-find manifest-block-re content)]
    (->> (str/split-lines raw-body)
         (map #(str/replace % #"^;; ?" ""))
         (str/join "\n")
         edn/read-string)))

(defn strip-embedded-manifest
  "Remove the embedded mutation manifest block from the end of the file."
  [content]
  (str/replace content manifest-block-re ""))

(defn strip-mutation-metadata
  "Remove legacy top-of-file date stamps and footer manifests."
  [content]
  (-> content
      strip-embedded-manifest
      (str/replace #"(?m)^;; mutation-tested: \d{4}-\d{2}-\d{2}\n?" "")))

(defn- form-kind [form]
  (when (seq? form)
    (first form)))

(defn- top-level-form-id [idx form]
  (let [head (form-kind form)]
    (cond
      (and (#{'def 'defn 'defn- 'defmacro 'defmulti} head)
           (symbol? (second form)))
      (str head "/" (second form))

      (and (= 'defmethod head)
           (symbol? (second form)))
      (str head "/" (second form) "/" (pr-str (nth form 2 nil)))

      :else
      (str "form/" idx "/" (or head :literal)))))

(defn top-level-form-manifest
  "Summarize top-level forms for incremental mutation selection."
  [forms]
  (mapv
    (fn [idx form]
      {:id (top-level-form-id idx form)
       :kind (str (or (form-kind form) :literal))
       :line (-> form meta :line)
       :end-line (-> form meta :end-line)
       :hash (str (hash (pr-str form)))})
    (range)
    forms))

(defn module-hash
  "Semantic hash of the module based on parsed top-level forms."
  [forms]
  (str (hash (pr-str forms))))

(defn changed-form-indices
  "Return current form indices whose top-level form hash differs from the manifest."
  [forms manifest]
  (let [current (top-level-form-manifest forms)
        previous-by-id (into {} (map (juxt :id identity) (:forms manifest)))]
    (->> current
         (keep-indexed
           (fn [idx form-entry]
             (let [previous (get previous-by-id (:id form-entry))]
               (when (or (nil? previous)
                         (not= (:hash previous) (:hash form-entry)))
                 idx))))
         set)))

(defn build-embedded-manifest
  "Create the embedded footer manifest for the current file contents."
  [forms date-str]
  {:version 1
   :tested-at date-str
   :module-hash (module-hash forms)
   :forms (top-level-form-manifest forms)})

(defn embed-mutation-manifest
  "Replace the footer manifest with an updated one."
  [content manifest]
  (let [base (strip-mutation-metadata content)
        body (->> (pr-str manifest)
                  str/split-lines
                  (map #(str ";; " %))
                  (str/join "\n"))]
    (str (str/trimr base)
         "\n\n"
         manifest-start-line
         "\n"
         body
         "\n"
         manifest-end-line
         "\n")))

(defn- backup-path [source-path]
  (str source-path ".mutation-backup"))

(defn- save-backup! [source-path content]
  (spit (backup-path source-path) content))

(defn- restore-from-backup! [source-path]
  (let [bp (backup-path source-path)]
    (when (.exists (File. bp))
      (spit source-path (slurp bp))
      (.delete (File. bp))
      true)))

(defn- cleanup-backup! [source-path]
  (let [f (File. (backup-path source-path))]
    (when (.exists f) (.delete f))))

(defn read-source-forms
  "Parse a source string into a vector of top-level forms.
   Handles .cljc reader conditionals."
  [source-str]
  (let [rdr (reader-types/source-logging-push-back-reader source-str)
        opts {:read-cond :allow :features #{:clj} :eof ::eof}]
    (loop [forms []]
      (let [form (reader/read opts rdr)]
        (if (= ::eof form)
          forms
          (recur (conj forms form)))))))

(defn discover-all-mutations
  "Find all mutation sites across a vector of top-level forms.
   Returns a flat vector of mutation sites with :form-index added."
  [forms]
  (vec (mapcat
         (fn [idx form]
           (map #(assoc % :form-index idx)
                (mutations/find-mutations form)))
         (range) forms)))

(defn partition-by-coverage
  "Split sites into [covered uncovered] based on covered-lines set.
   Sites with nil :line are treated as covered. If covered-lines is nil,
   all sites are treated as covered."
  [sites covered-lines]
  (if (nil? covered-lines)
    [sites []]
    (let [covered? #(or (nil? (:line %)) (contains? covered-lines (:line %)))
          grouped (group-by covered? sites)]
      [(vec (get grouped true [])) (vec (get grouped false []))])))

(defn- token-pattern
  "Build a regex pattern that matches only the specific token, not substrings.
   ;; TOKEN BOUNDARY SAFETY: Each token regex must match ONLY the intended
   ;; token, never a substring of a larger token. Test cases to verify:
   ;;   \"=\" must NOT match inside \"not=\", \">=\", \"<=\"
   ;;   \"0\" must NOT match inside \"10\", \"0.5\", \"100\"
   ;;   \"1\" must NOT match inside \"10\", \"100\", \"1.5\"
   ;;   \"<\" must NOT match inside \"<=\"
   ;;   \">\" must NOT match inside \">=\"
   ;;   \"+\" as arithmetic must NOT match inside \"+foo\" (namespace-qualified)"
  [token]
  (let [s (str token)]
    (or ({"="    (re-pattern "(?<![><=!])=(?!=)")
          "not=" (re-pattern "not=")
          ">"    (re-pattern ">(?!=)")
          ">="   (re-pattern ">=")
          "<"    (re-pattern "<(?!=)")
          "<="   (re-pattern "<=")} s)
        (when (re-matches #"\d+" s)
          (re-pattern (str "(?<!\\d|\\.)" (java.util.regex.Pattern/quote s) "(?!\\d|\\.)")))
        (when (re-matches #"[a-zA-Z].*" s)
          (re-pattern (str "(?<![a-zA-Z0-9_-])" (java.util.regex.Pattern/quote s) "(?![a-zA-Z0-9_-])")))
        (re-pattern (str "(?<=[\\s(])" (java.util.regex.Pattern/quote s) "(?=[\\s)])")))))

(defn mutate-source-text
  "Replace a single token in the original source text, preserving formatting.
   Uses :line and :column from the mutation site to target the right occurrence."
  [original-content site]
  (let [lines (str/split original-content #"\n" -1)
        line-idx (dec (:line site))
        line (nth lines line-idx)
        pat (token-pattern (:original site))
        col (:column site)
        replaced (if col
                   (let [search-start (max 0 (- col 2))
                         prefix (subs line 0 search-start)
                         suffix (subs line search-start)
                         new-suffix (str/replace-first suffix pat (str (:mutant site)))]
                     (str prefix new-suffix))
                   (str/replace-first line pat (str (:mutant site))))
        new-lines (assoc lines line-idx replaced)
        result (str/join "\n" new-lines)]
    result))

(defn mutate-and-test
  "Apply one mutation, write file, run all specs, restore original.
   Returns {:site site :result :killed/:survived :timeout? bool}."
  [source-path original-content _forms site timeout-ms test-command]
  (try
    (spit source-path (mutate-source-text original-content site))
    (let [result (runner/run-specs timeout-ms nil test-command)]
      {:site site
       :result (if (= :timeout result) :killed result)
       :timeout? (= :timeout result)})
    (finally
      (spit source-path original-content))))

(defn mutate-and-test-in-dir
  "Apply one mutation in a worker directory, run specs there, restore.
   Returns {:site site :result :killed/:survived :timeout? bool}."
  [worker-dir source-rel-path original-content site timeout-ms test-command]
  (let [worker-source (str worker-dir "/" source-rel-path)]
    (try
      (spit worker-source (mutate-source-text original-content site))
      (let [result (runner/run-specs timeout-ms worker-dir test-command)]
        {:site site
         :result (if (= :timeout result) :killed result)
         :timeout? (= :timeout result)})
      (finally
        (spit worker-source original-content)))))

(defn- result-label [r]
  (cond
    (:timeout? r) "TIMEOUT"
    (= :killed (:result r)) "KILLED"
    :else "SURVIVED"))

(defn- format-line [i total r]
  (format "[%3d/%d] %-8s  L%-4d %s%n"
          (inc i) total (result-label r) (or (:line (:site r)) 0) (:description (:site r))))

(defn- format-survivor [r]
  (format "  #%d  L%-4d %s%n" (inc (or (:index (:site r)) 0)) (or (:line (:site r)) 0) (:description (:site r))))

(defn format-report
  "Format mutation testing results as a console report string."
  [source-path results uncovered-count]
  (let [total (count results)
        killed (count (filter #(= :killed (:result %)) results))
        pct (if (zero? total) 0.0 (* 100.0 (/ killed total)))
        survivors (filter #(= :survived (:result %)) results)]
    (str
      (format "=== Mutation Testing: %s ===%n" source-path)
      (format "Found %d mutation sites.%n%n" total)
      (apply str (map-indexed #(format-line %1 total %2) results))
      (format "%n=== Summary ===%n")
      (format "%d/%d mutants killed (%.1f%%)%n" killed total pct)
      (when (pos? uncovered-count)
        (format "%d uncovered mutations skipped%n" uncovered-count))
      (when (seq survivors)
        (str "Survivors:\n"
             (apply str (map format-survivor survivors)))))))

(defn- parse-lines
  [value]
  (set (map #(parse-long (str/trim %))
            (str/split value #","))))

(def ^:private default-options
  {:source-path nil
   :lines nil
   :since-last-run false
   :mutate-all false
   :mutation-warning 50
   :timeout-factor 10
   :test-command "clj -M:spec --tag ~no-mutate"
   :max-workers nil})

(defn- usage-error [message]
  {:error message :usage usage-summary})

(defn- ensure-source-path [options]
  (let [source-path (:source-path options)]
    (cond
      (nil? source-path) (usage-error "Missing source file argument.")
      (not (.exists (File. ^String source-path)))
      (usage-error (str "Source file not found: " source-path))
      :else options)))

(defn- parse-positive-int-option [value option-name]
  (let [n (parse-long value)]
    (if (and n (pos? n))
      n
      (usage-error (str "Invalid value for " option-name ". Expected a positive integer.")))))

(defn- assoc-valid-option [options key parsed]
  (if (:error parsed)
    parsed
    (assoc options key parsed)))

(defn- parse-lines-option [options value]
  (if (or (:since-last-run options) (:mutate-all options))
    (usage-error "Cannot combine --lines with --since-last-run or --mutate-all.")
    (let [parsed-lines (parse-lines value)]
      (if (every? some? parsed-lines)
        (assoc options :lines parsed-lines)
        (usage-error "Invalid value for --lines. Expected comma-separated integers.")))))

(defn- parse-timeout-factor-option [options value]
  (assoc-valid-option options :timeout-factor (parse-positive-int-option value "--timeout-factor")))

(defn- parse-test-command-option [options value]
  (if (str/blank? value)
    (usage-error "Missing value for --test-command.")
    (assoc options :test-command value)))

(defn- parse-max-workers-option [options value]
  (assoc-valid-option options :max-workers (parse-positive-int-option value "--max-workers")))

(defn- parse-mutation-warning-option [options value]
  (assoc-valid-option options :mutation-warning (parse-positive-int-option value "--mutation-warning")))

(def ^:private option-updaters
  {"--lines" parse-lines-option
   "--mutation-warning" parse-mutation-warning-option
   "--timeout-factor" parse-timeout-factor-option
   "--test-command" parse-test-command-option
   "--max-workers" parse-max-workers-option})

(defn- update-arg-option [options option-key value]
  ((get option-updaters option-key) options value))

(defn- consume-option [options arg rest-args]
  (cond
    (= "--since-last-run" arg)
    (if (or (:lines options) (:mutate-all options))
      [rest-args (usage-error "Cannot combine --since-last-run with --lines or --mutate-all.")]
      [rest-args (assoc options :since-last-run true)])

    (= "--mutate-all" arg)
    (if (or (:lines options) (:since-last-run options))
      [rest-args (usage-error "Cannot combine --mutate-all with --lines or --since-last-run.")]
      [rest-args (assoc options :mutate-all true)])

    (contains? option-updaters arg)
    (if-let [value (first rest-args)]
      [(rest rest-args) (update-arg-option options arg value)]
      [rest-args (usage-error (str "Missing value for " arg "."))])

    (str/starts-with? arg "--")
    [rest-args (usage-error (str "Unknown option: " arg))]

    (:source-path options)
    [rest-args (usage-error (str "Unexpected extra argument: " arg))]

    :else
    [rest-args (assoc options :source-path arg)]))

(defn validate-args
  "Validate command-line arguments.
   Returns {:help true :usage ...} for --help,
   {:source-path ... :lines ... :timeout-factor ... :test-command ... :max-workers ...}
   or {:error \"message\" :usage ...}."
  [args]
  (if (some #{"--help"} args)
    {:help true :usage usage-summary}
    (loop [[arg & rest-args] args
           options default-options]
      (if (nil? arg)
        (ensure-source-path options)
        (let [[remaining updated-options] (consume-option options arg rest-args)]
          (if (:error updated-options)
            updated-options
            (recur remaining updated-options)))))))

(defn- print-progress [i total result site]
  (println (format "[%3d/%d] %-8s  L%-4d %s"
                   (inc i) total
                   (result-label result)
                   (or (:line site) 0)
                   (:description site)))
  (flush))

(defn run-mutations-parallel
  "Run all mutation sites in parallel using worker directories.
   Returns results sorted by site index."
  [sites source-path original-content timeout-ms max-workers test-command]
  (let [run-base-dir (workers/new-run-base-dir worker-root-dir)
        n-workers (max 1 (min (count sites)
                              (.availableProcessors (Runtime/getRuntime))
                              (or max-workers Integer/MAX_VALUE)))
        worker-dirs (workers/create-worker-dirs!
                      run-base-dir source-path original-content n-workers)
        queue (java.util.concurrent.LinkedBlockingQueue. ^java.util.Collection (vec sites))
        results (java.util.concurrent.ConcurrentLinkedQueue.)
        counter (atom 0)
        total (count sites)
        lock (Object.)
        futures (mapv
                  (fn [dir]
                    (future
                      (loop []
                        (when-let [site (.poll queue)]
                          (let [r (mutate-and-test-in-dir dir source-path
                                                          original-content site timeout-ms
                                                          test-command)
                                n (swap! counter inc)]
                            (.add results r)
                            (locking lock
                              (print-progress (dec n) total r site))
                            (recur))))))
                  worker-dirs)]
    (try
      (run! deref futures)
      (vec (sort-by #(:index (:site %)) results))
      (finally
        (workers/cleanup-worker-dirs! run-base-dir)))))

(defn- print-uncovered [uncovered]
  (when (seq uncovered)
    (println (format "\n=== Coverage Gaps (%d mutations on uncovered lines) ==="
                     (count uncovered)))
    (doseq [site uncovered]
      (println (format "  line %d: %s" (:line site) (:description site))))))

(defn- print-summary [killed total pct survivors uncovered-count]
  (println (format "\n=== Summary ==="))
  (println (format "%d/%d mutants killed (%.1f%%)" killed total pct))
  (when (pos? uncovered-count)
    (println (format "%d uncovered mutations skipped" uncovered-count)))
  (when (seq survivors)
    (println "Survivors:")
    (doseq [r survivors]
      (println (format "  #%d  L%-4d %s"
                       (inc (:index (:site r)))
                       (or (:line (:site r)) 0)
                       (:description (:site r)))))))

(defn- now-str []
  (.format (java.time.OffsetDateTime/now)
           java.time.format.DateTimeFormatter/ISO_OFFSET_DATE_TIME))

(defn- filter-by-lines
  "Filter mutation sites to only those on the specified lines."
  [sites lines]
  (if lines
    (vec (filter #(contains? lines (:line %)) sites))
    sites))

(defn- filter-by-form-indices
  "Filter mutation sites to only those within the selected top-level forms."
  [sites form-indices]
  (if form-indices
    (vec (filter #(contains? form-indices (:form-index %)) sites))
    sites))

(defn- mutation-run-context [source-path since-last-run]
  (let [original-content (slurp source-path)
        prior-manifest (extract-embedded-manifest original-content)
        analysis-content (strip-mutation-metadata original-content)
        forms (read-source-forms analysis-content)
        current-module-hash (module-hash forms)
        module-unchanged? (and since-last-run
                               prior-manifest
                               (= current-module-hash (:module-hash prior-manifest)))
        changed-forms (when (and since-last-run prior-manifest (not module-unchanged?))
                        (changed-form-indices forms prior-manifest))
        all-sites (discover-all-mutations forms)
        covered-lines (coverage/load-coverage source-path)
        [covered-sites uncovered] (partition-by-coverage all-sites covered-lines)]
    {:original-content original-content
     :prev-date (extract-mutation-date original-content)
     :prior-manifest prior-manifest
     :analysis-content analysis-content
     :forms forms
     :module-unchanged? module-unchanged?
     :all-sites all-sites
     :covered-sites covered-sites
     :uncovered uncovered
     :sites nil
     :manifest-content (embed-mutation-manifest analysis-content
                                                (build-embedded-manifest forms (now-str)))
     :changed-forms changed-forms}))

(defn- default-since-last-run? [lines since-last-run mutate-all prior-manifest]
  (and (nil? lines)
       (not mutate-all)
       (or since-last-run (some? prior-manifest))))

(defn- select-mutation-sites [covered-sites lines since-last-run module-unchanged? changed-forms]
  (cond
    lines (filter-by-lines covered-sites lines)
    module-unchanged? []
    since-last-run (filter-by-form-indices covered-sites changed-forms)
    :else covered-sites))

(defn- print-mutation-warning [warning-threshold total-mutations]
  (when (> total-mutations warning-threshold)
    (println (format "WARNING: Found %d mutations. Consider splitting this module." total-mutations))))

(defn- print-run-header [source-path prev-date all-sites covered-sites uncovered lines since-last-run prior-manifest module-unchanged? sites warning-threshold]
  (println (format "=== Mutation Testing: %s ===" source-path))
  (when prev-date
    (println (format "Previous mutation test: %s" prev-date)))
  (println (format "Found %d mutation sites (%d covered, %d uncovered)."
                   (count all-sites) (count covered-sites) (count uncovered)))
  (print-mutation-warning warning-threshold (count all-sites))
  (when lines
    (println (format "Filtering to lines: %s → %d mutations to test."
                     (str/join "," (sort lines)) (count sites))))
  (when since-last-run
    (if prior-manifest
      (if module-unchanged?
        (println "Module hash unchanged; no mutations to test.")
        (println (format "Filtering to changed top-level forms → %d mutations to test."
                         (count sites))))
      (println "No prior embedded manifest found; running all covered mutations.")))
  (println))

(defn- summarize-results [results lines since-last-run uncovered]
  (let [killed (count (filter #(= :killed (:result %)) results))
        total (count results)
        pct (if (zero? total) 0.0 (* 100.0 (/ killed total)))
        survivors (filter #(= :survived (:result %)) results)]
    (print-summary killed total pct survivors (if (or lines since-last-run) 0 (count uncovered)))))

(defn- run-mutation-suite [sites source-path analysis-content timeout-ms max-workers test-command]
  (if (seq sites)
    (run-mutations-parallel sites source-path analysis-content timeout-ms max-workers test-command)
    []))

(defn- with-baseline [test-command timeout-factor on-pass]
  (print "Baseline: ") (flush)
  (let [{baseline-result :result elapsed-ms :elapsed-ms} (runner/run-specs-timed test-command)
        timeout-ms (* timeout-factor elapsed-ms)]
    (if (= :survived baseline-result)
      (do
        (println (format "PASS (%.1fs, timeout %.1fs)"
                         (/ elapsed-ms 1000.0) (/ timeout-ms 1000.0)))
        (on-pass timeout-ms))
      (println "FAIL — specs do not pass without mutations. Aborting."))))

(defn- exit! [status]
  (System/exit status))

(defn run-mutation-testing
  "Run mutation testing on a single source file.
   Optional lines arg: set of line numbers to restrict testing to.
   Default behavior uses differential mutation when a manifest exists.
   Optional timeout-factor arg: positive integer multiplier for mutation timeout.
   Optional test-command arg: command string for running tests.
   Optional max-workers arg: positive integer worker cap.
   Optional mutate-all arg forces a full run even when a manifest exists.
   Optional mutation-warning arg controls the warning threshold."
  ([source-path] (run-mutation-testing source-path nil 10 "clj -M:spec --tag ~no-mutate" nil false false 50))
  ([source-path lines] (run-mutation-testing source-path lines 10 "clj -M:spec --tag ~no-mutate" nil false false 50))
  ([source-path lines timeout-factor test-command max-workers]
   (run-mutation-testing source-path lines timeout-factor test-command max-workers false false 50))
  ([source-path lines timeout-factor test-command max-workers since-last-run]
   (run-mutation-testing source-path lines timeout-factor test-command max-workers since-last-run false 50))
  ([source-path lines timeout-factor test-command max-workers since-last-run mutate-all mutation-warning]
   (when (restore-from-backup! source-path)
     (println "Restored source from backup (previous run was interrupted)."))
   (let [manifest-detected? (some? (extract-embedded-manifest (slurp source-path)))
         effective-since-last-run (default-since-last-run? lines since-last-run mutate-all manifest-detected?)
         {:keys [prev-date prior-manifest analysis-content all-sites covered-sites uncovered
                 module-unchanged? changed-forms manifest-content] :as context}
         (mutation-run-context source-path effective-since-last-run)
         sites (select-mutation-sites covered-sites lines effective-since-last-run module-unchanged? changed-forms)]
     (print-run-header source-path prev-date all-sites covered-sites uncovered lines effective-since-last-run prior-manifest module-unchanged? sites mutation-warning)
     (with-baseline
       test-command
       timeout-factor
       (fn [timeout-ms]
         (when-not (or lines effective-since-last-run)
           (print-uncovered uncovered))
         (save-backup! source-path manifest-content)
         (try
           (let [results (run-mutation-suite sites source-path analysis-content timeout-ms max-workers test-command)]
             (summarize-results results lines effective-since-last-run uncovered)
             (spit source-path manifest-content))
           (finally
             (cleanup-backup! source-path))))))))

(defn- handle-main-result [validated]
  (cond
    (:help validated)
    (println (:usage validated))

    (:error validated)
    (do
      (println (:error validated))
      (println)
      (print (:usage validated))
      (exit! 1))

    :else
    (run-mutation-testing (:source-path validated)
                          (:lines validated)
                          (:timeout-factor validated)
                          (:test-command validated)
                          (:max-workers validated)
                          (:since-last-run validated)
                          (:mutate-all validated)
                          (:mutation-warning validated))))

(defn -main [& args]
  (handle-main-result (validate-args (vec args))))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-03-12T08:20:07.00581-05:00", :module-hash "-185118755", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line 10, :hash "-1124715915"} {:id "def/mutation-comment-re", :kind "def", :line 12, :end-line 12, :hash "739874186"} {:id "def/manifest-start-line", :kind "def", :line 13, :end-line 13, :hash "-1825565512"} {:id "def/manifest-end-line", :kind "def", :line 14, :end-line 14, :hash "744098285"} {:id "def/manifest-block-re", :kind "def", :line 15, :end-line 21, :hash "-1957750279"} {:id "def/worker-root-dir", :kind "def", :line 22, :end-line 22, :hash "-2021164772"} {:id "def/usage-summary", :kind "def", :line 24, :end-line 36, :hash "-681892759"} {:id "form/7/declare", :kind "declare", :line 38, :end-line 38, :hash "668603200"} {:id "defn/extract-mutation-date", :kind "defn", :line 40, :end-line 47, :hash "-432201093"} {:id "defn/stamp-mutation-date", :kind "defn", :line 49, :end-line 55, :hash "-1659751816"} {:id "form/10/declare", :kind "declare", :line 57, :end-line 57, :hash "977462642"} {:id "defn/extract-embedded-manifest", :kind "defn", :line 59, :end-line 66, :hash "-1254073010"} {:id "defn/strip-embedded-manifest", :kind "defn", :line 68, :end-line 71, :hash "-676213746"} {:id "defn/strip-mutation-metadata", :kind "defn", :line 73, :end-line 78, :hash "-177931011"} {:id "defn-/form-kind", :kind "defn-", :line 80, :end-line 82, :hash "184035403"} {:id "defn-/top-level-form-id", :kind "defn-", :line 84, :end-line 96, :hash "317205413"} {:id "defn/top-level-form-manifest", :kind "defn", :line 98, :end-line 109, :hash "-1650053116"} {:id "defn/module-hash", :kind "defn", :line 111, :end-line 114, :hash "194895947"} {:id "defn/changed-form-indices", :kind "defn", :line 116, :end-line 128, :hash "-1968882967"} {:id "defn/build-embedded-manifest", :kind "defn", :line 130, :end-line 136, :hash "-1435501691"} {:id "defn/embed-mutation-manifest", :kind "defn", :line 138, :end-line 153, :hash "203699243"} {:id "defn-/backup-path", :kind "defn-", :line 155, :end-line 156, :hash "-1243914595"} {:id "defn-/save-backup!", :kind "defn-", :line 158, :end-line 159, :hash "952728175"} {:id "defn-/restore-from-backup!", :kind "defn-", :line 161, :end-line 166, :hash "463418019"} {:id "defn-/cleanup-backup!", :kind "defn-", :line 168, :end-line 170, :hash "-94981140"} {:id "defn/read-source-forms", :kind "defn", :line 172, :end-line 182, :hash "805550565"} {:id "defn/discover-all-mutations", :kind "defn", :line 184, :end-line 192, :hash "-1677740425"} {:id "defn/partition-by-coverage", :kind "defn", :line 194, :end-line 203, :hash "-310317698"} {:id "defn-/token-pattern", :kind "defn-", :line 205, :end-line 227, :hash "66308841"} {:id "defn/mutate-source-text", :kind "defn", :line 229, :end-line 247, :hash "1458706597"} {:id "defn/mutate-and-test", :kind "defn", :line 249, :end-line 260, :hash "994141332"} {:id "defn/mutate-and-test-in-dir", :kind "defn", :line 262, :end-line 274, :hash "515973440"} {:id "defn-/result-label", :kind "defn-", :line 276, :end-line 280, :hash "-1992427708"} {:id "defn-/format-line", :kind "defn-", :line 282, :end-line 284, :hash "-1409718304"} {:id "defn-/format-survivor", :kind "defn-", :line 286, :end-line 287, :hash "791497139"} {:id "defn/format-report", :kind "defn", :line 289, :end-line 306, :hash "1538731633"} {:id "defn-/parse-lines", :kind "defn-", :line 308, :end-line 311, :hash "537054094"} {:id "def/default-options", :kind "def", :line 313, :end-line 321, :hash "-946161944"} {:id "defn-/usage-error", :kind "defn-", :line 323, :end-line 324, :hash "1974487799"} {:id "defn-/ensure-source-path", :kind "defn-", :line 326, :end-line 332, :hash "-1213637125"} {:id "defn-/parse-positive-int-option", :kind "defn-", :line 334, :end-line 338, :hash "-1335572082"} {:id "defn-/assoc-valid-option", :kind "defn-", :line 340, :end-line 343, :hash "-799587466"} {:id "defn-/parse-lines-option", :kind "defn-", :line 345, :end-line 351, :hash "1841142554"} {:id "defn-/parse-timeout-factor-option", :kind "defn-", :line 353, :end-line 354, :hash "352916015"} {:id "defn-/parse-test-command-option", :kind "defn-", :line 356, :end-line 359, :hash "-371328726"} {:id "defn-/parse-max-workers-option", :kind "defn-", :line 361, :end-line 362, :hash "1042636703"} {:id "defn-/parse-mutation-warning-option", :kind "defn-", :line 364, :end-line 365, :hash "1462324008"} {:id "def/option-updaters", :kind "def", :line 367, :end-line 372, :hash "-477315895"} {:id "defn-/update-arg-option", :kind "defn-", :line 374, :end-line 375, :hash "744944125"} {:id "defn-/consume-option", :kind "defn-", :line 377, :end-line 401, :hash "1819531821"} {:id "defn/validate-args", :kind "defn", :line 403, :end-line 418, :hash "-1447215481"} {:id "defn-/print-progress", :kind "defn-", :line 420, :end-line 426, :hash "-1449441128"} {:id "defn/run-mutations-parallel", :kind "defn", :line 428, :end-line 461, :hash "1638055606"} {:id "defn-/print-uncovered", :kind "defn-", :line 463, :end-line 468, :hash "-1031300309"} {:id "defn-/print-summary", :kind "defn-", :line 470, :end-line 481, :hash "-647776276"} {:id "defn-/now-str", :kind "defn-", :line 483, :end-line 485, :hash "-478732191"} {:id "defn-/filter-by-lines", :kind "defn-", :line 487, :end-line 492, :hash "-616966867"} {:id "defn-/filter-by-form-indices", :kind "defn-", :line 494, :end-line 499, :hash "-1118224439"} {:id "defn-/mutation-run-context", :kind "defn-", :line 501, :end-line 527, :hash "-1216574230"} {:id "defn-/default-since-last-run?", :kind "defn-", :line 529, :end-line 532, :hash "-443101211"} {:id "defn-/select-mutation-sites", :kind "defn-", :line 534, :end-line 539, :hash "-1170808831"} {:id "defn-/print-mutation-warning", :kind "defn-", :line 541, :end-line 543, :hash "1259284107"} {:id "defn-/print-run-header", :kind "defn-", :line 545, :end-line 562, :hash "-484710272"} {:id "defn-/summarize-results", :kind "defn-", :line 564, :end-line 569, :hash "1535233648"} {:id "defn-/run-mutation-suite", :kind "defn-", :line 571, :end-line 574, :hash "238287669"} {:id "defn-/with-baseline", :kind "defn-", :line 576, :end-line 585, :hash "-1807980763"} {:id "defn-/exit!", :kind "defn-", :line 587, :end-line 588, :hash "-479327949"} {:id "defn/run-mutation-testing", :kind "defn", :line 590, :end-line 627, :hash "1962727837"} {:id "defn-/handle-main-result", :kind "defn-", :line 629, :end-line 649, :hash "-516921664"} {:id "defn/-main", :kind "defn", :line 651, :end-line 652, :hash "-798927235"}]}
;; clj-mutate-manifest-end
