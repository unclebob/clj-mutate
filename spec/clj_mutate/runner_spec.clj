(ns clj-mutate.runner-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.runner :as runner])
  (:import [java.io File]))

(describe "run-specs"
  (it "accepts a timeout-ms and dir parameter"
    (let [temp-dir (doto (File. (str "target/test-runner-" (System/nanoTime)))
                     (.mkdirs))
          dir-path (.getPath temp-dir)]
      (try
        ;; Just verify the 2-arity exists — call with nonsense dir
        ;; that will fail fast (no deps.edn). The point is no
        ;; ArityException.
        (should-not-throw
          (try (runner/run-specs 100 dir-path)
               (catch Exception e
                 (when (instance? clojure.lang.ArityException e)
                   (throw e)))))
        (finally
          (.delete temp-dir)))))

  (it "accepts a timeout-ms, dir, and test-command parameter"
    (let [temp-dir (doto (File. (str "target/test-runner-" (System/nanoTime)))
                     (.mkdirs))
          dir-path (.getPath temp-dir)]
      (try
        (should-not-throw
          (try (runner/run-specs 100 dir-path "clj -M:spec")
               (catch Exception e
                 (when (instance? clojure.lang.ArityException e)
                   (throw e)))))
        (finally
          (.delete temp-dir))))))

(describe "command->argv"
  (it "falls back to the default command when input is blank"
    (should= ["clj" "-M:spec" "--tag" "~no-mutate"] (#'runner/command->argv "   ")))

  (it "splits a non-blank command on whitespace"
    (should= ["clj" "-M:all-tests"] (#'runner/command->argv "clj   -M:all-tests"))))

(describe "start-output-drainer!"
  (it "marks the stream-draining thread as daemon"
    (let [daemon? (atom nil)
          started? (atom false)
          thread (Object.)
          process (proxy [Process] []
                    (getInputStream [] (java.io.ByteArrayInputStream. (.getBytes ""))))]
      (with-redefs [runner/create-thread (fn [_] thread)
                    runner/mark-thread-daemon! (fn [t] (reset! daemon? (= thread t)) t)
                    runner/start-thread! (fn [t] (reset! started? (= thread t)) t)]
        (#'runner/start-output-drainer! process)
        (should= true @daemon?)
        (should= true @started?)))))

(describe "wait-for-process"
  (it "waits without timeout when timeout-ms is nil"
    (let [waited? (atom false)
          process (proxy [Process] []
                    (waitFor
                      ([] (do (reset! waited? true) 0))
                      ([timeout unit] false)))]
      (should= true (#'runner/wait-for-process process nil))
      (should= true @waited?)))

  (it "waits with the given timeout when timeout-ms is present"
    (let [wait-args (atom nil)
          process (proxy [Process] []
                    (waitFor
                      ([] false)
                      ([timeout unit]
                       (reset! wait-args [timeout unit])
                       true)))]
      (should= true (#'runner/wait-for-process process 25))
      (should= [25 java.util.concurrent.TimeUnit/MILLISECONDS] @wait-args))))

(describe "run-specs internals"
  (it "destroys the process when it times out"
    (let [destroyed? (atom false)
          process (proxy [Process] []
                    (exitValue [] 0)
                    (destroyForcibly [] (reset! destroyed? true) this))]
      (with-redefs [runner/start-process (fn [_ _] process)
                    runner/start-output-drainer! (fn [_] nil)
                    runner/wait-for-process (fn [_ _] false)]
        (should= :timeout (runner/run-specs 10 nil "clj -M:spec"))
        (should= true @destroyed?))))

  (it "returns killed when the process exits non-zero"
    (let [process (proxy [Process] []
                    (exitValue [] 1)
                    (destroyForcibly [] this))]
      (with-redefs [runner/start-process (fn [_ _] process)
                    runner/start-output-drainer! (fn [_] nil)
                    runner/wait-for-process (fn [_ _] true)]
        (should= :killed (runner/run-specs 10 nil "clj -M:spec"))))))

(describe "run-specs-timed"
  (it "measures elapsed time around run-specs"
    (with-redefs [runner/run-specs (fn [_ _ _] :killed)
                  runner/current-time-ms (let [values (atom [100 145])]
                                           (fn []
                                             (let [current (first @values)]
                                               (swap! values rest)
                                               current)))]
      (should= {:result :killed :elapsed-ms 45}
               (runner/run-specs-timed "clj -M:spec")))))

(run-specs)
