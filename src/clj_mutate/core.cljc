(ns clj-mutate.core
  (:require [clj-mutate.cli :as cli]
            [clj-mutate.execution :as execution]
            [clj-mutate.manifest :as manifest]
            [clj-mutate.source :as source]
            [clj-mutate.workflow :as workflow]
            [clj-mutate.workers :as workers]))

(def usage-summary cli/usage-summary)

(def extract-mutation-date manifest/extract-mutation-date)
(def stamp-mutation-date manifest/stamp-mutation-date)
(def extract-embedded-manifest manifest/extract-embedded-manifest)
(def strip-mutation-metadata manifest/strip-mutation-metadata)
(def top-level-form-manifest manifest/top-level-form-manifest)
(def module-hash manifest/module-hash)
(def changed-form-indices manifest/changed-form-indices)
(def build-embedded-manifest manifest/build-embedded-manifest)
(def embed-mutation-manifest manifest/embed-mutation-manifest)
(def save-backup! manifest/save-backup!)
(def restore-from-backup! manifest/restore-from-backup!)
(def cleanup-backup! manifest/cleanup-backup!)

(def read-source-forms source/read-source-forms)
(def discover-all-mutations source/discover-all-mutations)
(def partition-by-coverage source/partition-by-coverage)
(def mutate-source-text source/mutate-source-text)

(def mutate-and-test execution/mutate-and-test)
(def mutate-and-test-in-dir execution/mutate-and-test-in-dir)
(def format-report execution/format-report)
(def ^:private print-progress execution/print-progress)

(def validate-args cli/validate-args)

(def mutation-run-context workflow/mutation-run-context)
(def select-mutation-sites workflow/select-mutation-sites)
(def differential-site-counts workflow/differential-site-counts)
(def ^:private print-uncovered workflow/print-uncovered)
(def ^:private scan-mutation-sites workflow/scan-mutation-sites)
(def ^:private print-run-header workflow/print-run-header)
(def summarize-results workflow/summarize-results)
(def with-baseline workflow/with-baseline)

(defn run-mutations-parallel
  [sites source-path original-content timeout-ms max-workers test-command]
  (let [run-base-dir (workers/new-run-base-dir execution/worker-root-dir)
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

(defn run-mutation-suite
  [sites source-path analysis-content timeout-ms max-workers test-command]
  (if (seq sites)
    (run-mutations-parallel sites source-path analysis-content timeout-ms max-workers test-command)
    []))

(defn- exit!
  [status]
  (System/exit status))

(defn- shutdown-runtime!
  []
  (shutdown-agents))

(defn- update-manifest!
  [source-path]
  (when (restore-from-backup! source-path)
    (println "Restored source from backup (previous run was interrupted)."))
  (let [content (slurp source-path)
        analysis-content (strip-mutation-metadata content)
        forms (read-source-forms analysis-content)
        manifest-content (embed-mutation-manifest
                           analysis-content
                           (build-embedded-manifest forms (manifest/now-str)))]
    (spit source-path manifest-content)
    (println (str "Updated manifest: " source-path))))

(defn run-mutation-testing
  ([source-path] (run-mutation-testing source-path nil 10 cli/default-test-command nil false false 50))
  ([source-path lines] (run-mutation-testing source-path lines 10 cli/default-test-command nil false false 50))
  ([source-path lines timeout-factor test-command max-workers]
   (run-mutation-testing source-path lines timeout-factor test-command max-workers false false 50))
  ([source-path lines timeout-factor test-command max-workers since-last-run]
   (run-mutation-testing source-path lines timeout-factor test-command max-workers since-last-run false 50))
  ([source-path lines timeout-factor test-command max-workers since-last-run mutate-all mutation-warning]
   (when (restore-from-backup! source-path)
     (println "Restored source from backup (previous run was interrupted)."))
   (let [manifest-detected? (some? (extract-embedded-manifest (slurp source-path)))
         effective-since-last-run (workflow/default-since-last-run? lines since-last-run mutate-all manifest-detected?)
         {:keys [prev-date prior-manifest analysis-content all-sites covered-sites uncovered
                 module-unchanged? changed-forms manifest-content
                 new-form-indices manifest-violating-form-indices]}
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
             (summarize-results results
                                lines
                                effective-since-last-run
                                uncovered
                                (when (and effective-since-last-run prior-manifest)
                                  (differential-site-counts sites new-form-indices manifest-violating-form-indices)))
             (spit source-path manifest-content))
           (finally
             (cleanup-backup! source-path))))))))

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
    (scan-mutation-sites (:source-path validated)
                         (:mutation-warning validated))

    (:update-manifest validated)
    (update-manifest! (:source-path validated))

    :else
    (run-mutation-testing (:source-path validated)
                          (:lines validated)
                          (:timeout-factor validated)
                          (:test-command validated)
                          (:max-workers validated)
                          (:since-last-run validated)
                          (:mutate-all validated)
                          (:mutation-warning validated))))

(defn -main
  [& args]
  (try
    (handle-main-result (validate-args (vec args)))
    (finally
      (shutdown-runtime!))))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-03-13T07:03:23.64339-05:00", :module-hash "855620441", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line 7, :hash "-861550392"} {:id "def/usage-summary", :kind "def", :line 9, :end-line 9, :hash "-1041004115"} {:id "def/extract-mutation-date", :kind "def", :line 11, :end-line 11, :hash "-1062118604"} {:id "def/stamp-mutation-date", :kind "def", :line 12, :end-line 12, :hash "-1440451022"} {:id "def/extract-embedded-manifest", :kind "def", :line 13, :end-line 13, :hash "-635050615"} {:id "def/strip-mutation-metadata", :kind "def", :line 14, :end-line 14, :hash "-8711223"} {:id "def/top-level-form-manifest", :kind "def", :line 15, :end-line 15, :hash "1975075233"} {:id "def/module-hash", :kind "def", :line 16, :end-line 16, :hash "1918681287"} {:id "def/changed-form-indices", :kind "def", :line 17, :end-line 17, :hash "43323891"} {:id "def/build-embedded-manifest", :kind "def", :line 18, :end-line 18, :hash "-936382977"} {:id "def/embed-mutation-manifest", :kind "def", :line 19, :end-line 19, :hash "-757751694"} {:id "def/save-backup!", :kind "def", :line 20, :end-line 20, :hash "2126896465"} {:id "def/restore-from-backup!", :kind "def", :line 21, :end-line 21, :hash "-1092217530"} {:id "def/cleanup-backup!", :kind "def", :line 22, :end-line 22, :hash "1988961436"} {:id "def/read-source-forms", :kind "def", :line 24, :end-line 24, :hash "-1520520130"} {:id "def/discover-all-mutations", :kind "def", :line 25, :end-line 25, :hash "432183859"} {:id "def/partition-by-coverage", :kind "def", :line 26, :end-line 26, :hash "-209749297"} {:id "def/mutate-source-text", :kind "def", :line 27, :end-line 27, :hash "-19377979"} {:id "def/mutate-and-test", :kind "def", :line 29, :end-line 29, :hash "-1038016521"} {:id "def/mutate-and-test-in-dir", :kind "def", :line 30, :end-line 30, :hash "693295212"} {:id "def/format-report", :kind "def", :line 31, :end-line 31, :hash "-568892377"} {:id "def/print-progress", :kind "def", :line 32, :end-line 32, :hash "-727297614"} {:id "def/validate-args", :kind "def", :line 34, :end-line 34, :hash "-651611374"} {:id "def/mutation-run-context", :kind "def", :line 36, :end-line 36, :hash "-1499598464"} {:id "def/select-mutation-sites", :kind "def", :line 37, :end-line 37, :hash "1053871457"} {:id "def/differential-site-counts", :kind "def", :line 38, :end-line 38, :hash "-1466039838"} {:id "def/print-uncovered", :kind "def", :line 39, :end-line 39, :hash "-213588584"} {:id "def/scan-mutation-sites", :kind "def", :line 40, :end-line 40, :hash "-1645560296"} {:id "def/print-run-header", :kind "def", :line 41, :end-line 41, :hash "-886479159"} {:id "def/summarize-results", :kind "def", :line 42, :end-line 42, :hash "-171328939"} {:id "def/with-baseline", :kind "def", :line 43, :end-line 43, :hash "-659612404"} {:id "defn/run-mutations-parallel", :kind "defn", :line 45, :end-line 76, :hash "568304018"} {:id "defn/run-mutation-suite", :kind "defn", :line 78, :end-line 82, :hash "1170417424"} {:id "defn-/exit!", :kind "defn-", :line 84, :end-line 86, :hash "-479327949"} {:id "defn-/shutdown-runtime!", :kind "defn-", :line 88, :end-line 90, :hash "404102440"} {:id "defn-/update-manifest!", :kind "defn-", :line 92, :end-line 103, :hash "-97871951"} {:id "defn/run-mutation-testing", :kind "defn", :line 105, :end-line 140, :hash "-490809903"} {:id "defn-/handle-main-result", :kind "defn-", :line 142, :end-line 170, :hash "-1772722479"} {:id "defn/-main", :kind "defn", :line 172, :end-line 177, :hash "2057772400"}]}
;; clj-mutate-manifest-end
