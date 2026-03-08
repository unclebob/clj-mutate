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

(defn create-worker-dirs!
  "Create n worker directories under base-dir. Each gets symlinks to
   the project config (bb.edn or deps.edn), spec/, cache dirs, and a
   real copy of the source file at source-rel-path."
  [base-dir source-rel-path original-content n]
  (let [project-root (System/getProperty "user.dir")
        config (project/config-file project-root)
        bb? (project/bb-project? project-root)]
    (vec
      (for [i (range n)]
        (let [dir-path (str base-dir "/worker-" i)
              dir (File. dir-path)
              source-file (File. dir source-rel-path)]
          (.mkdirs (.getParentFile source-file))
          (symlink! (str dir-path "/" config)
                    (str project-root "/" config))
          (symlink! (str dir-path "/spec")
                    (str project-root "/spec"))
          (if bb?
            (when (.exists (File. (str project-root "/.babashka")))
              (symlink! (str dir-path "/.babashka")
                        (str project-root "/.babashka")))
            (when (.exists (File. (str project-root "/.cpcache")))
              (symlink! (str dir-path "/.cpcache")
                        (str project-root "/.cpcache"))))
          (spit (.getPath source-file) original-content)
          dir-path)))))

(defn cleanup-worker-dirs!
  "Remove the base directory and all worker directories."
  [base-dir]
  (delete-recursive! (File. base-dir)))
