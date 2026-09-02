(ns clj-mutate.cli
  (:require [clojure.string :as str]
            [clj-mutate.project :as project])
  (:import [java.io File]))

(defn default-test-command
  []
  (project/default-test-command))

(def usage-summary
  (str
    "Usage: clj -M:mutate <source-file.cljc> [options]\n"
    "   or: bb mutate <source-file.cljc> [options]\n"
    "\n"
    "Options:\n"
    "  --scan                Report mutation counts without running tests or coverage\n"
    "  --update-manifest     Rewrite the embedded manifest without running mutations\n"
    "  --reuse-lcov          Reuse existing LCOV data without refreshing coverage\n"
    "  --lines L1,L2,...      Run only mutations on these source lines\n"
    "  --since-last-run       Run only mutations in changed top-level forms since last successful run\n"
    "  --mutate-all           Run all covered mutations even if a manifest exists\n"
    "  --mutation-warning N   Warn when more than N mutations are found (default 100)\n"
    "  --timeout-factor N     Mutation timeout multiplier vs baseline (default 10)\n"
    "  --test-command CMD     Test command to run (default: bb spec or clj -M:spec --tag ~no-mutate)\n"
    "  --max-workers N        Limit parallel workers to N (positive integer)\n"
    "  --help                 Print this help and exit\n"))

(def default-options
  {:source-path nil
   :scan false
   :update-manifest false
   :reuse-lcov false
   :lines nil
   :since-last-run false
   :mutate-all false
   :mutation-warning 100
   :timeout-factor 10
   :test-command nil
   :max-workers nil})

(defn- initial-options
  []
  (assoc default-options :test-command (default-test-command)))

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

(defn- assoc-valid-option
  [options key parsed]
  (if (:error parsed)
    parsed
    (assoc options key parsed)))

(defn- parse-lines-option
  [options value]
  (if (or (:scan options) (:update-manifest options) (:since-last-run options) (:mutate-all options))
    (usage-error "Cannot combine --lines with --scan, --update-manifest, --since-last-run, or --mutate-all.")
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
        (assoc options :test-command value))))

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
   "--max-workers" parse-max-workers-option})

(defn- update-arg-option
  [options option-key value]
  ((get option-updaters option-key) options value))

(defn- execution-options-present?
  [options]
  (or (:lines options)
      (:since-last-run options)
      (:mutate-all options)
      (not= 10 (:timeout-factor options))
      (not= (default-test-command) (:test-command options))
      (:max-workers options)))

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
    :conflict-fn #(or (:scan %) (:update-manifest %) (:lines %) (:mutate-all %))
    :message "Cannot combine --since-last-run with --scan, --update-manifest, --lines, or --mutate-all."}
   "--mutate-all"
   {:key :mutate-all
    :conflict-fn #(or (:scan %) (:update-manifest %) (:lines %) (:since-last-run %))
    :message "Cannot combine --mutate-all with --scan, --update-manifest, --lines, or --since-last-run."}})

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
    [rest-args (assoc options :reuse-lcov true)]

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
        (ensure-source-path options)
        (let [[remaining updated-options] (consume-option options arg rest-args)]
          (if (:error updated-options)
            updated-options
            (recur remaining updated-options)))))))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-09-02T14:42:44.74711-05:00", :module-hash "967202217", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line 4, :hash "324014734"} {:id "defn/default-test-command", :kind "defn", :line 6, :end-line 8, :hash "1423082516"} {:id "def/usage-summary", :kind "def", :line 10, :end-line 26, :hash "2141497425"} {:id "def/default-options", :kind "def", :line 28, :end-line 39, :hash "-1800562664"} {:id "defn-/initial-options", :kind "defn-", :line 41, :end-line 43, :hash "-870668115"} {:id "defn-/parse-lines", :kind "defn-", :line 45, :end-line 48, :hash "1324155486"} {:id "defn-/usage-error", :kind "defn-", :line 50, :end-line 52, :hash "1974487799"} {:id "defn-/ensure-source-path", :kind "defn-", :line 54, :end-line 60, :hash "-1213637125"} {:id "defn-/parse-positive-int-option", :kind "defn-", :line 62, :end-line 67, :hash "-1335572082"} {:id "defn-/assoc-valid-option", :kind "defn-", :line 69, :end-line 73, :hash "-799587466"} {:id "defn-/parse-lines-option", :kind "defn-", :line 75, :end-line 82, :hash "-1155732671"} {:id "defn-/reject-scan-or-update", :kind "defn-", :line 84, :end-line 87, :hash "-487893733"} {:id "defn-/parse-int-execution-option", :kind "defn-", :line 89, :end-line 92, :hash "1396211893"} {:id "defn-/parse-timeout-factor-option", :kind "defn-", :line 94, :end-line 96, :hash "-609526442"} {:id "defn-/parse-test-command-option", :kind "defn-", :line 98, :end-line 103, :hash "-1186903327"} {:id "defn-/parse-max-workers-option", :kind "defn-", :line 105, :end-line 107, :hash "-1666636307"} {:id "defn-/parse-mutation-warning-option", :kind "defn-", :line 109, :end-line 111, :hash "1462324008"} {:id "def/option-updaters", :kind "def", :line 113, :end-line 118, :hash "-477315895"} {:id "defn-/update-arg-option", :kind "defn-", :line 120, :end-line 122, :hash "744944125"} {:id "defn-/execution-options-present?", :kind "defn-", :line 124, :end-line 131, :hash "1007510315"} {:id "defn-/enable-unless-conflict", :kind "defn-", :line 133, :end-line 137, :hash "1158487389"} {:id "def/flag-enablers", :kind "def", :line 139, :end-line 155, :hash "-2085160842"} {:id "defn-/consume-flag", :kind "defn-", :line 157, :end-line 160, :hash "519063344"} {:id "defn-/consume-valued-option", :kind "defn-", :line 162, :end-line 166, :hash "1562100389"} {:id "defn-/consume-option", :kind "defn-", :line 168, :end-line 187, :hash "-1230383573"} {:id "defn/validate-args", :kind "defn", :line 189, :end-line 200, :hash "285148323"}]}
;; clj-mutate-manifest-end
