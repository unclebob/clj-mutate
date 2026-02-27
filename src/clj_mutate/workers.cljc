(ns clj-mutate.workers
  (:import [java.io File]
           [java.nio.file Files Paths]))

(defn- symlink! [link-path target-path]
  (let [link (Paths/get link-path (into-array String []))
        target (.toAbsolutePath (Paths/get target-path (into-array String [])))]
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
   deps.edn, spec/, .cpcache/ (if present), and a real copy of the
   source file at source-rel-path."
  [base-dir source-rel-path original-content n]
  (let [project-root (System/getProperty "user.dir")]
    (vec
      (for [i (range n)]
        (let [dir-path (str base-dir "/worker-" i)
              dir (File. dir-path)
              source-file (File. dir source-rel-path)]
          (.mkdirs (.getParentFile source-file))
          (symlink! (str dir-path "/deps.edn")
                    (str project-root "/deps.edn"))
          (symlink! (str dir-path "/spec")
                    (str project-root "/spec"))
          (when (.exists (File. (str project-root "/.cpcache")))
            (symlink! (str dir-path "/.cpcache")
                      (str project-root "/.cpcache")))
          (spit (.getPath source-file) original-content)
          dir-path)))))

(defn cleanup-worker-dirs!
  "Remove the base directory and all worker directories."
  [base-dir]
  (delete-recursive! (File. base-dir)))
