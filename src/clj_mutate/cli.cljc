(ns clj-mutate.cli
  (:require [clojure.string :as str]
            [clj-mutate.project :as project])
  (:import [java.io File]))

(defn default-test-command
  []
  (project/default-test-command))

(def usage-summary
  (str
    "Usage: clj -M:mutate <source-file> [options]\n"
    "   or: bb mutate <source-file> [options]\n"
    "\n"
    "Options:\n"
    "  --scan                Report mutation counts without running tests or coverage\n"
    "  --update-manifest     Write an unverified manifest without running mutations\n"
    "  --reuse-lcov          Reuse matching LCOV without refreshing coverage\n"
    "  --coverage-command CMD Command that generates LCOV for the worker test profile\n"
    "  --no-coverage         Disable LCOV filtering and run all selected mutants\n"
    "  --lines L1,L2,...     Run only mutations on these source lines\n"
    "  --mutation ID         Run one mutation by M-number or persistent mutation ID\n"
    "  --since-last-run       Run only mutations in changed top-level forms since last successful run\n"
    "  --mutate-all           Run all covered mutations even if a manifest exists\n"
    "  --mutation-warning N   Warn when more than N mutations are found (default 100)\n"
    "  --timeout-factor N     Mutation timeout multiplier vs baseline (default 10)\n"
    "  --test-command CMD     Command run for the baseline and every mutant\n"
    "  --test-roots D1,D2     Existing project-relative roots shared by test and coverage\n"
    "  --max-workers N        Limit parallel workers to N (positive integer)\n"
    "  --help                 Print this help and exit\n"
    "\n"
    "Runtime defaults:\n"
    "  JVM Clojure: tests use clj -M:spec --tag ~no-mutate; coverage uses clj -M:cov --lcov.\n"
    "  Babashka: tests use bb spec --tag ~no-mutate; coverage is disabled until configured.\n"
    "\n"
    "Profile and manifest rules:\n"
    "  A custom --test-command requires --coverage-command or --no-coverage.\n"
    "  Test and coverage commands must select the same roots. If they cannot be inferred\n"
    "  from the selected deps.edn alias or bb.edn task, provide --test-roots.\n"
    "  Runs narrowed by --lines or --mutation do not update the verified manifest.\n"))

(def default-options
  {:source-path nil
   :scan false
   :update-manifest false
   :reuse-lcov false
   :coverage-command nil
   :lines nil
   :mutation nil
   :since-last-run false
   :mutate-all false
   :mutation-warning 100
   :timeout-factor 10
   :test-command nil
   :test-roots nil
   :max-workers nil
   :explicit-options #{}})

(defn- initial-options
  []
  (assoc default-options
         :test-command (default-test-command)
         :coverage-command (project/default-coverage-command)))

(defn- parse-lines
  [value]
  (set (map #(parse-long (str/trim %))
            (str/split value #","))))

(defn- usage-error
  [message]
  {:error message :usage usage-summary})

(defn- ensure-source-path
  [options]
  (let [source-path (:source-path options)]
    (cond
      (nil? source-path) (usage-error "Missing source file argument.")
      (not (.exists (File. ^String source-path))) (usage-error (str "Source file not found: " source-path))
      :else options)))

(defn- parse-positive-int-option
  [value option-name]
  (let [n (parse-long value)]
    (if (and n (pos? n))
      n
      (usage-error (str "Invalid value for " option-name ". Expected a positive integer.")))))

(defn- mark-explicit
  [options key]
  (update options :explicit-options (fnil conj #{}) key))

(defn- assoc-valid-option
  [options key parsed]
  (if (:error parsed)
    parsed
    (mark-explicit (assoc options key parsed) key)))

(defn- parse-lines-option
  [options value]
  (if (or (:scan options) (:update-manifest options) (:since-last-run options) (:mutate-all options) (:mutation options))
    (usage-error "Cannot combine --lines with --scan, --update-manifest, --since-last-run, --mutate-all, or --mutation.")
    (let [parsed-lines (parse-lines value)]
      (if (every? some? parsed-lines)
        (assoc options :lines parsed-lines)
        (usage-error "Invalid value for --lines. Expected comma-separated integers.")))))

(defn- reject-scan-or-update
  [options option-name]
  (when (or (:scan options) (:update-manifest options))
    (usage-error (str "Cannot combine --scan or --update-manifest with " option-name "."))))

(defn- parse-int-execution-option
  [options value option-key option-name]
  (or (reject-scan-or-update options option-name)
      (assoc-valid-option options option-key (parse-positive-int-option value option-name))))

(defn- parse-timeout-factor-option
  [options value]
  (parse-int-execution-option options value :timeout-factor "--timeout-factor"))

(defn- parse-test-command-option
  [options value]
  (or (reject-scan-or-update options "--test-command")
      (if (str/blank? value)
        (usage-error "Missing value for --test-command.")
        (mark-explicit (assoc options :test-command value) :test-command))))

(defn- parse-test-roots-option
  [options value]
  (or (reject-scan-or-update options "--test-roots")
      (let [roots (->> (str/split value #",")
                       (map str/trim)
                       (remove str/blank?)
                       vec)
            normalized (project/test-profile-roots
                         (System/getProperty "user.dir") nil roots)]
        (cond
          (empty? roots)
          (usage-error "Missing value for --test-roots.")

          (not= (count (distinct roots)) (count normalized))
          (usage-error "Invalid value for --test-roots. Every entry must be an existing project-relative directory.")

          :else
          (mark-explicit (assoc options :test-roots normalized) :test-roots)))))

(defn- parse-coverage-command-option
  [options value]
  (or (reject-scan-or-update options "--coverage-command")
      (when (false? (:coverage-command options))
        (usage-error "Cannot combine --coverage-command with --no-coverage."))
      (if (str/blank? value)
        (usage-error "Missing value for --coverage-command.")
        (mark-explicit (assoc options :coverage-command value) :coverage-command))))

(defn- parse-mutation-option
  [options value]
  (if (or (:scan options) (:update-manifest options) (:lines options)
          (:since-last-run options) (:mutate-all options))
    (usage-error "Cannot combine --mutation with --scan, --update-manifest, --lines, --since-last-run, or --mutate-all.")
    (if (str/blank? value)
      (usage-error "Missing value for --mutation.")
      (assoc options :mutation value))))

(defn- parse-max-workers-option
  [options value]
  (parse-int-execution-option options value :max-workers "--max-workers"))

(defn- parse-mutation-warning-option
  [options value]
  (assoc-valid-option options :mutation-warning (parse-positive-int-option value "--mutation-warning")))

(def option-updaters
  {"--lines" parse-lines-option
   "--mutation-warning" parse-mutation-warning-option
   "--timeout-factor" parse-timeout-factor-option
   "--test-command" parse-test-command-option
   "--test-roots" parse-test-roots-option
   "--coverage-command" parse-coverage-command-option
   "--mutation" parse-mutation-option
   "--max-workers" parse-max-workers-option})

(defn- update-arg-option
  [options option-key value]
  ((get option-updaters option-key) options value))

(defn- execution-options-present?
  [options]
  (or (:lines options)
      (:since-last-run options)
      (:mutate-all options)
      (:mutation options)
      (:reuse-lcov options)
      (contains? (:explicit-options options) :timeout-factor)
      (contains? (:explicit-options options) :test-command)
      (contains? (:explicit-options options) :test-roots)
      (contains? (:explicit-options options) :coverage-command)
      (contains? (:explicit-options options) :max-workers)))

(defn- enable-unless-conflict
  [options rest-args flag-key conflict-fn message]
  (if (conflict-fn options)
    [rest-args (usage-error message)]
    [rest-args (assoc options flag-key true)]))

(def flag-enablers
  {"--scan"
   {:key :scan
    :conflict-fn #(or (:update-manifest %) (execution-options-present? %))
    :message "Cannot combine --scan with --update-manifest or mutation execution options."}
   "--update-manifest"
   {:key :update-manifest
    :conflict-fn #(or (:scan %) (execution-options-present? %))
    :message "Cannot combine --update-manifest with --scan or mutation execution options."}
   "--since-last-run"
   {:key :since-last-run
    :conflict-fn #(or (:scan %) (:update-manifest %) (:lines %) (:mutation %) (:mutate-all %))
    :message "Cannot combine --since-last-run with --scan, --update-manifest, --lines, --mutation, or --mutate-all."}
   "--mutate-all"
   {:key :mutate-all
    :conflict-fn #(or (:scan %) (:update-manifest %) (:lines %) (:mutation %) (:since-last-run %))
    :message "Cannot combine --mutate-all with --scan, --update-manifest, --lines, --mutation, or --since-last-run."}})

(defn- consume-flag
  [options arg rest-args]
  (let [{:keys [key conflict-fn message]} (get flag-enablers arg)]
    (enable-unless-conflict options rest-args key conflict-fn message)))

(defn- consume-valued-option
  [options arg rest-args]
  (if-let [value (first rest-args)]
    [(rest rest-args) (update-arg-option options arg value)]
    [rest-args (usage-error (str "Missing value for " arg "."))]))

(defn- consume-option
  [options arg rest-args]
  (cond
    (contains? flag-enablers arg)
    (consume-flag options arg rest-args)

    (= "--reuse-lcov" arg)
    (if-let [err (or (reject-scan-or-update options "--reuse-lcov")
                     (when (false? (:coverage-command options))
                       (usage-error "Cannot combine --reuse-lcov with --no-coverage.")))]
      [rest-args err]
      [rest-args (assoc options :reuse-lcov true)])

    (= "--no-coverage" arg)
    (if-let [err (or (reject-scan-or-update options "--no-coverage")
                     (when (:reuse-lcov options)
                       (usage-error "Cannot combine --no-coverage with --reuse-lcov."))
                     (when (contains? (:explicit-options options) :coverage-command)
                       (usage-error "Cannot combine --no-coverage with --coverage-command.")))]
      [rest-args err]
      [rest-args (mark-explicit (assoc options :coverage-command false)
                                :coverage-command)])

    (contains? option-updaters arg)
    (consume-valued-option options arg rest-args)

    (str/starts-with? arg "--")
    [rest-args (usage-error (str "Unknown option: " arg))]

    (:source-path options)
    [rest-args (usage-error (str "Unexpected extra argument: " arg))]

    :else
    [rest-args (assoc options :source-path arg)]))

(defn validate-args
  [args]
  (if (some #{"--help"} args)
    {:help true :usage usage-summary}
    (loop [[arg & rest-args] args
           options (initial-options)]
      (if (nil? arg)
        (let [checked (ensure-source-path options)
              custom-test? (contains? (:explicit-options checked) :test-command)
              roots (when-not (:error checked)
                      (project/test-profile-roots
                        (System/getProperty "user.dir")
                        (:test-command checked)
                        (:test-roots checked)))]
          (cond
            (:error checked)
            checked

            (and custom-test?
                 (not (contains? (:explicit-options checked) :coverage-command)))
            (usage-error "A custom --test-command requires --coverage-command or --no-coverage so coverage cannot silently use a different test population.")

            (and custom-test? (empty? roots))
            (usage-error "A custom --test-command must expose test roots through its project alias/task or --test-roots.")

            (and (:coverage-command checked)
                 (not (project/commands-share-test-profile?
                        (System/getProperty "user.dir")
                        (:test-command checked)
                        (:coverage-command checked)
                        (:test-roots checked))))
            (usage-error "The test and coverage commands do not identify the same test roots. Use matching project aliases or --test-roots.")

            :else
            checked))
        (let [[remaining updated-options] (consume-option options arg rest-args)]
          (if (:error updated-options)
            updated-options
            (recur remaining updated-options)))))))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-09-02T15:17:34.607675-05:00", :module-hash "-380628144", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line nil, :hash "324014734"} {:id "defn/default-test-command", :kind "defn", :line 6, :end-line nil, :hash "1423082516"} {:id "def/usage-summary", :kind "def", :line 10, :end-line nil, :hash "2141497425"} {:id "def/default-options", :kind "def", :line 28, :end-line nil, :hash "-1800562664"} {:id "defn-/initial-options", :kind "defn-", :line 41, :end-line nil, :hash "-870668115"} {:id "defn-/parse-lines", :kind "defn-", :line 45, :end-line nil, :hash "2092092850"} {:id "defn-/usage-error", :kind "defn-", :line 50, :end-line nil, :hash "1974487799"} {:id "defn-/ensure-source-path", :kind "defn-", :line 54, :end-line nil, :hash "-1213637125"} {:id "defn-/parse-positive-int-option", :kind "defn-", :line 62, :end-line nil, :hash "-1335572082"} {:id "defn-/assoc-valid-option", :kind "defn-", :line 69, :end-line nil, :hash "-799587466"} {:id "defn-/parse-lines-option", :kind "defn-", :line 75, :end-line nil, :hash "-1155732671"} {:id "defn-/reject-scan-or-update", :kind "defn-", :line 84, :end-line nil, :hash "-487893733"} {:id "defn-/parse-int-execution-option", :kind "defn-", :line 89, :end-line nil, :hash "1396211893"} {:id "defn-/parse-timeout-factor-option", :kind "defn-", :line 94, :end-line nil, :hash "-609526442"} {:id "defn-/parse-test-command-option", :kind "defn-", :line 98, :end-line nil, :hash "-1186903327"} {:id "defn-/parse-max-workers-option", :kind "defn-", :line 105, :end-line nil, :hash "-1666636307"} {:id "defn-/parse-mutation-warning-option", :kind "defn-", :line 109, :end-line nil, :hash "1462324008"} {:id "def/option-updaters", :kind "def", :line 113, :end-line nil, :hash "-477315895"} {:id "defn-/update-arg-option", :kind "defn-", :line 120, :end-line nil, :hash "744944125"} {:id "defn-/execution-options-present?", :kind "defn-", :line 124, :end-line nil, :hash "1007510315"} {:id "defn-/enable-unless-conflict", :kind "defn-", :line 133, :end-line nil, :hash "1158487389"} {:id "def/flag-enablers", :kind "def", :line 139, :end-line nil, :hash "909526321"} {:id "defn-/consume-flag", :kind "defn-", :line 157, :end-line nil, :hash "519063344"} {:id "defn-/consume-valued-option", :kind "defn-", :line 162, :end-line nil, :hash "1562100389"} {:id "defn-/consume-option", :kind "defn-", :line 168, :end-line nil, :hash "-1230383573"} {:id "defn/validate-args", :kind "defn", :line 189, :end-line nil, :hash "285148323"}]}
;; clj-mutate-manifest-end
