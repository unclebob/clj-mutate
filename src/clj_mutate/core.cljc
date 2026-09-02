(ns clj-mutate.core
  (:require [clj-mutate.cli :as cli]
            [clj-mutate.workflow :as workflow]))

(defn- exit!
  [status]
  (System/exit status))

(defn- shutdown-runtime!
  []
  (shutdown-agents))

(defn- handle-main-result
  [validated]
  (cond
    (:help validated)
    (println (:usage validated))

    (:error validated)
    (do
      (println (:error validated))
      (println)
      (print (:usage validated))
      (exit! 1))

    (:scan validated)
    (workflow/scan-mutation-sites (:source-path validated)
                                  (:mutation-warning validated))

    (:update-manifest validated)
    (workflow/update-manifest! (:source-path validated))

    :else
    (try
      (workflow/run-mutation-testing (:source-path validated)
                                     (:lines validated)
                                     (:timeout-factor validated)
                                     (:test-command validated)
                                     (:max-workers validated)
                                     (:since-last-run validated)
                                     (:mutate-all validated)
                                     (:mutation-warning validated)
                                     (:reuse-lcov validated))
      (catch clojure.lang.ExceptionInfo ex
        (let [{:keys [reason lcov-path]} (ex-data ex)]
          (if (= :missing-lcov-for-reuse reason)
            (do
              (println (format "Error: --reuse-lcov was requested, but %s does not exist." lcov-path))
              (println "Run without --reuse-lcov once to generate coverage.")
              (exit! 1))
            (throw ex)))))))

(defn -main
  [& args]
  (try
    (handle-main-result (cli/validate-args (vec args)))
    (finally
      (shutdown-runtime!))))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-09-02T15:15:12.388163-05:00", :module-hash "-1767853646", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line nil, :hash "-532135196"} {:id "defn-/exit!", :kind "defn-", :line 5, :end-line nil, :hash "-479327949"} {:id "defn-/shutdown-runtime!", :kind "defn-", :line 9, :end-line nil, :hash "404102440"} {:id "defn-/handle-main-result", :kind "defn-", :line 13, :end-line nil, :hash "27023360"} {:id "defn/-main", :kind "defn", :line 53, :end-line nil, :hash "1900473086"}]}
;; clj-mutate-manifest-end
