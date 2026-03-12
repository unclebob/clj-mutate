(ns clj-mutate.cli
  (:require [clojure.string :as str])
  (:import [java.io File]))

(def default-test-command "clj -M:spec --tag ~no-mutate")

(def usage-summary
  (str
    "Usage: clj -M:mutate <source-file.cljc> [options]\n"
    "\n"
    "Options:\n"
    "  --scan                Report mutation counts without running tests or coverage\n"
    "  --update-manifest     Rewrite the embedded manifest without running mutations\n"
    "  --lines L1,L2,...      Run only mutations on these source lines\n"
    "  --since-last-run       Run only mutations in changed top-level forms since last successful run\n"
    "  --mutate-all           Run all covered mutations even if a manifest exists\n"
    "  --mutation-warning N   Warn when more than N mutations are found (default 50)\n"
    "  --timeout-factor N     Mutation timeout multiplier vs baseline (default 10)\n"
    "  --test-command CMD     Test command to run (default \"clj -M:spec --tag ~no-mutate\")\n"
    "  --max-workers N        Limit parallel workers to N (positive integer)\n"
    "  --help                 Print this help and exit\n"))

(def default-options
  {:source-path nil
   :scan false
   :update-manifest false
   :lines nil
   :since-last-run false
   :mutate-all false
   :mutation-warning 50
   :timeout-factor 10
   :test-command default-test-command
   :max-workers nil})

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

(defn- parse-timeout-factor-option
  [options value]
  (if (or (:scan options) (:update-manifest options))
    (usage-error "Cannot combine --scan or --update-manifest with --timeout-factor.")
    (assoc-valid-option options :timeout-factor (parse-positive-int-option value "--timeout-factor"))))

(defn- parse-test-command-option
  [options value]
  (cond
    (or (:scan options) (:update-manifest options)) (usage-error "Cannot combine --scan or --update-manifest with --test-command.")
    (str/blank? value) (usage-error "Missing value for --test-command.")
    :else (assoc options :test-command value)))

(defn- parse-max-workers-option
  [options value]
  (if (or (:scan options) (:update-manifest options))
    (usage-error "Cannot combine --scan or --update-manifest with --max-workers.")
    (assoc-valid-option options :max-workers (parse-positive-int-option value "--max-workers"))))

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

(defn- consume-option
  [options arg rest-args]
  (cond
    (= "--scan" arg)
    (if (or (:update-manifest options) (:lines options) (:since-last-run options) (:mutate-all options)
            (not= 10 (:timeout-factor options))
            (not= default-test-command (:test-command options))
            (:max-workers options))
      [rest-args (usage-error "Cannot combine --scan with --update-manifest or mutation execution options.")]
      [rest-args (assoc options :scan true)])

    (= "--update-manifest" arg)
    (if (or (:scan options) (:lines options) (:since-last-run options) (:mutate-all options)
            (not= 10 (:timeout-factor options))
            (not= default-test-command (:test-command options))
            (:max-workers options))
      [rest-args (usage-error "Cannot combine --update-manifest with --scan or mutation execution options.")]
      [rest-args (assoc options :update-manifest true)])

    (= "--since-last-run" arg)
    (if (or (:scan options) (:update-manifest options) (:lines options) (:mutate-all options))
      [rest-args (usage-error "Cannot combine --since-last-run with --scan, --update-manifest, --lines, or --mutate-all.")]
      [rest-args (assoc options :since-last-run true)])

    (= "--mutate-all" arg)
    (if (or (:scan options) (:update-manifest options) (:lines options) (:since-last-run options))
      [rest-args (usage-error "Cannot combine --mutate-all with --scan, --update-manifest, --lines, or --since-last-run.")]
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

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-03-12T10:35:14.944742-05:00", :module-hash "1467604435", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line 3, :hash "1659780056"} {:id "def/default-test-command", :kind "def", :line 5, :end-line 5, :hash "-1188861817"} {:id "def/usage-summary", :kind "def", :line 7, :end-line 21, :hash "-117038009"} {:id "def/default-options", :kind "def", :line 23, :end-line 33, :hash "-187288951"} {:id "defn-/parse-lines", :kind "defn-", :line 35, :end-line 38, :hash "-1934187243"} {:id "defn-/usage-error", :kind "defn-", :line 40, :end-line 42, :hash "1974487799"} {:id "defn-/ensure-source-path", :kind "defn-", :line 44, :end-line 50, :hash "-1213637125"} {:id "defn-/parse-positive-int-option", :kind "defn-", :line 52, :end-line 57, :hash "-1335572082"} {:id "defn-/assoc-valid-option", :kind "defn-", :line 59, :end-line 63, :hash "-799587466"} {:id "defn-/parse-lines-option", :kind "defn-", :line 65, :end-line 72, :hash "-1155732671"} {:id "defn-/parse-timeout-factor-option", :kind "defn-", :line 74, :end-line 78, :hash "-535890960"} {:id "defn-/parse-test-command-option", :kind "defn-", :line 80, :end-line 85, :hash "-1514324896"} {:id "defn-/parse-max-workers-option", :kind "defn-", :line 87, :end-line 91, :hash "2102075008"} {:id "defn-/parse-mutation-warning-option", :kind "defn-", :line 93, :end-line 95, :hash "1462324008"} {:id "def/option-updaters", :kind "def", :line 97, :end-line 102, :hash "-477315895"} {:id "defn-/update-arg-option", :kind "defn-", :line 104, :end-line 106, :hash "744944125"} {:id "defn-/consume-option", :kind "defn-", :line 108, :end-line 149, :hash "1685472801"} {:id "defn/validate-args", :kind "defn", :line 151, :end-line 162, :hash "-118263957"}]}
;; clj-mutate-manifest-end
