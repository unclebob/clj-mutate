;; mutation-tested: 2026-03-25
(ns clj-mutate.workers
  (:require [clj-mutate.project :as project])
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
  "Create a source tree overlay in the worker directory. The first
   segment of source-rel-path (e.g. \"src\") becomes a real directory
   in the worker. From there, each directory level along the path to
   the mutated file is a real directory with symlinks to siblings.
   The mutated file itself is a real file; everything else in the
   source tree is a symlink back to the original project."
  [worker-path project-root source-rel-path original-content]
  (let [segments (clojure.string/split source-rel-path #"/")
        source-file (File. worker-path source-rel-path)]
    (.mkdirs (.getParentFile source-file))
    ;; Start from the first segment (e.g. "src"), symlink siblings
    ;; at each directory level down to the mutated file
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
  "Create n worker directories under base-dir. Each gets symlinks to
   the project config (bb.edn or deps.edn), spec/, cache dirs, and a
   source tree overlay where only the mutated file is a real file."
  [base-dir source-rel-path original-content n]
  (let [project-root (System/getProperty "user.dir")
        config (project/config-file project-root)
        bb? (project/bb-project? project-root)]
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
