(ns clj-mutate.workers
  (:require [clj-mutate.project :as project]
            [clojure.string :as str])
  (:import [java.io File]
           [java.util UUID]
           [java.nio.file Files Paths]))

(defn new-run-base-dir
  "Return a unique per-run worker base directory under root-dir."
  [root-dir]
  (str root-dir "/run-" (str (UUID/randomUUID))))

(defn- symlink! [link-path target-path]
  (let [link (Paths/get link-path (into-array String []))
        target (.toAbsolutePath (Paths/get target-path (into-array String [])))]
    (Files/deleteIfExists link)
    (Files/createSymbolicLink link target (into-array java.nio.file.attribute.FileAttribute []))))

(defn- delete-recursive! [^File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [child (.listFiles f)]
        (if (Files/isSymbolicLink (.toPath child))
          (.delete child)
          (delete-recursive! child))))
    (.delete f)))

(defn- setup-source-overlay!
  "Create a source tree overlay in the worker directory."
  [worker-path project-root source-rel-path original-content]
  (let [segments (str/split source-rel-path #"/")
        source-file (File. worker-path source-rel-path)]
    (.mkdirs (.getParentFile source-file))
    (loop [depth 0 rel-path (first segments)]
      (let [next-depth (inc depth)]
        (when (< next-depth (count segments))
          (let [next-segment (nth segments next-depth)
                abs-dir (str project-root "/" rel-path)
                worker-dir (str worker-path "/" rel-path)
                real-dir (File. abs-dir)]
            (when (.isDirectory real-dir)
              (doseq [child (.listFiles real-dir)]
                (let [child-name (.getName child)]
                  (when (not= child-name next-segment)
                    (symlink! (str worker-dir "/" child-name)
                              (.getPath child))))))
            (recur next-depth (str rel-path "/" next-segment))))))
    (spit (.getPath source-file) original-content)))

(defn create-worker-dirs!
  "Create n worker directories under base-dir. Each gets a copy of the
   project config, a symlinked spec/, and a source overlay where only the
   mutated file is a real file."
  [base-dir source-rel-path original-content n]
  (let [project-root (System/getProperty "user.dir")
        config (project/config-file project-root)]
    (vec
      (for [i (range n)]
        (let [dir-path (str base-dir "/worker-" i)]
          (.mkdirs (File. dir-path))
          (spit (str dir-path "/" config)
                (slurp (str project-root "/" config)))
          (symlink! (str dir-path "/spec")
                    (str project-root "/spec"))
          (setup-source-overlay! dir-path project-root
                                 source-rel-path original-content)
          dir-path)))))

(defn cleanup-worker-dirs!
  "Remove the base directory and all worker directories."
  [base-dir]
  (delete-recursive! (File. base-dir)))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-03-12T09:09:42.853489-05:00", :module-hash "262639133", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line 4, :hash "1667957825"} {:id "defn/new-run-base-dir", :kind "defn", :line 6, :end-line 9, :hash "122525552"} {:id "defn-/symlink!", :kind "defn-", :line 11, :end-line 15, :hash "-1052323910"} {:id "defn-/delete-recursive!", :kind "defn-", :line 17, :end-line 24, :hash "593481821"} {:id "defn/create-worker-dirs!", :kind "defn", :line 26, :end-line 46, :hash "-1293715584"} {:id "defn/cleanup-worker-dirs!", :kind "defn", :line 48, :end-line 51, :hash "178299261"}]}
;; clj-mutate-manifest-end