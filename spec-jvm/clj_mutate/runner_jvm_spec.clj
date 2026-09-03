(ns clj-mutate.runner-jvm-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.runner :as runner]))

(describe "start-output-drainer! on the JVM"
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

(describe "wait-for-process on the JVM"
  (it "waits without timeout when timeout-ms is nil"
    (let [waited? (atom false)
          process (proxy [Process] []
                    (waitFor
                      ([] (do (reset! waited? true) 0))
                      ([_ _] false)))]
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

(describe "run-specs process internals on the JVM"
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

(run-specs)
