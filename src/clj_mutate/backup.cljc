(ns clj-mutate.backup
  (:import [java.io File]))

(defn- backup-path
  [source-path]
  (str source-path ".mutation-backup"))

(defn save-backup!
  [source-path content]
  (spit (backup-path source-path) content))

(defn restore-from-backup!
  [source-path]
  (let [bp (backup-path source-path)]
    (when (.exists (File. bp))
      (spit source-path (slurp bp))
      (.delete (File. bp))
      true)))

(defn cleanup-backup!
  [source-path]
  (let [f (File. (backup-path source-path))]
    (when (.exists f)
      (.delete f))))
