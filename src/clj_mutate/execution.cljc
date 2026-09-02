(ns clj-mutate.execution
  (:require [clj-mutate.runner :as runner]
            [clj-mutate.source :as source]
            [clj-mutate.workers :as workers]))

(def worker-root-dir "target/mutation-workers")

(defn- mutate-and-test-at
  [source-path original-content site timeout-ms test-command dir]
  (try
    (spit source-path (source/mutate-source-text original-content site))
    (let [result (runner/run-specs timeout-ms dir test-command)]
      {:site site
       :result (if (= :timeout result) :killed result)
       :timeout? (= :timeout result)})
    (finally
      (spit source-path original-content))))

(defn mutate-and-test
  [source-path original-content _forms site timeout-ms test-command]
  (mutate-and-test-at source-path original-content site timeout-ms test-command nil))

(defn mutate-and-test-in-dir
  [worker-dir source-rel-path original-content site timeout-ms test-command]
  (mutate-and-test-at (str worker-dir "/" source-rel-path)
                      original-content site timeout-ms test-command worker-dir))

(defn run-mutations-parallel
  ([sites source-path original-content timeout-ms max-workers test-command]
   (run-mutations-parallel sites source-path original-content timeout-ms max-workers test-command nil))
  ([sites source-path original-content timeout-ms max-workers test-command on-progress]
   (let [run-base-dir (workers/new-run-base-dir worker-root-dir)
         n-workers (max 1 (min (count sites)
                               (.availableProcessors (Runtime/getRuntime))
                               (or max-workers Integer/MAX_VALUE)))
         worker-dirs (workers/create-worker-dirs!
                       run-base-dir source-path original-content n-workers)
         queue (java.util.concurrent.LinkedBlockingQueue. ^java.util.Collection (vec sites))
         results (atom [])
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
                             (swap! results conj r)
                             (when on-progress
                               (locking lock
                                 (on-progress (dec n) total r site)))
                             (recur))))))
                   worker-dirs)]
     (try
       (run! deref futures)
       (vec (sort-by #(:index (:site %)) @results))
       (finally
         (workers/cleanup-worker-dirs! run-base-dir))))))
