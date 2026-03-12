(ns clj-mutate.workers
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

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-03-12T09:09:42.853489-05:00", :module-hash "262639133", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line 4, :hash "1667957825"} {:id "defn/new-run-base-dir", :kind "defn", :line 6, :end-line 9, :hash "122525552"} {:id "defn-/symlink!", :kind "defn-", :line 11, :end-line 15, :hash "-1052323910"} {:id "defn-/delete-recursive!", :kind "defn-", :line 17, :end-line 24, :hash "593481821"} {:id "defn/create-worker-dirs!", :kind "defn", :line 26, :end-line 46, :hash "-1293715584"} {:id "defn/cleanup-worker-dirs!", :kind "defn", :line 48, :end-line 51, :hash "178299261"}]}
;; clj-mutate-manifest-end
