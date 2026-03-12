(ns clj-mutate.execution
  (:require [clj-mutate.runner :as runner]
            [clj-mutate.source :as source]
            [clj-mutate.workers :as workers]))

(def worker-root-dir "target/mutation-workers")

(defn mutate-and-test
  [source-path original-content _forms site timeout-ms test-command]
  (try
    (spit source-path (source/mutate-source-text original-content site))
    (let [result (runner/run-specs timeout-ms nil test-command)]
      {:site site
       :result (if (= :timeout result) :killed result)
       :timeout? (= :timeout result)})
    (finally
      (spit source-path original-content))))

(defn mutate-and-test-in-dir
  [worker-dir source-rel-path original-content site timeout-ms test-command]
  (let [worker-source (str worker-dir "/" source-rel-path)]
    (try
      (spit worker-source (source/mutate-source-text original-content site))
      (let [result (runner/run-specs timeout-ms worker-dir test-command)]
        {:site site
         :result (if (= :timeout result) :killed result)
         :timeout? (= :timeout result)})
      (finally
        (spit worker-source original-content)))))

(defn- result-label
  [r]
  (cond
    (:timeout? r) "TIMEOUT"
    (= :killed (:result r)) "KILLED"
    :else "SURVIVED"))

(defn- format-line
  [i total r]
  (format "[%3d/%d] %-8s  L%-4d %s%n"
          (inc i) total (result-label r) (or (:line (:site r)) 0) (:description (:site r))))

(defn- format-survivor
  [r]
  (format "  #%d  L%-4d %s%n" (inc (or (:index (:site r)) 0)) (or (:line (:site r)) 0) (:description (:site r))))

(defn format-report
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

(defn print-progress
  [i total result site]
  (println (format "[%3d/%d] %-8s  L%-4d %s"
                   (inc i) total
                   (result-label result)
                   (or (:line site) 0)
                   (:description site)))
  (flush))

(defn run-mutations-parallel
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

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-03-12T09:07:06.502558-05:00", :module-hash "-992264678", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line 4, :hash "408477767"} {:id "def/worker-root-dir", :kind "def", :line 6, :end-line 6, :hash "-2021164772"} {:id "defn/mutate-and-test", :kind "defn", :line 8, :end-line 17, :hash "1030860974"} {:id "defn/mutate-and-test-in-dir", :kind "defn", :line 19, :end-line 29, :hash "-1129658651"} {:id "defn-/result-label", :kind "defn-", :line 31, :end-line 36, :hash "-1992427708"} {:id "defn-/format-line", :kind "defn-", :line 38, :end-line 41, :hash "-1409718304"} {:id "defn-/format-survivor", :kind "defn-", :line 43, :end-line 45, :hash "791497139"} {:id "defn/format-report", :kind "defn", :line 47, :end-line 63, :hash "-389031099"} {:id "defn/print-progress", :kind "defn", :line 65, :end-line 72, :hash "684719315"} {:id "defn/run-mutations-parallel", :kind "defn", :line 74, :end-line 105, :hash "-158430648"}]}
;; clj-mutate-manifest-end
