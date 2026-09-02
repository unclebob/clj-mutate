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

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-09-02T15:14:28.443044-05:00", :module-hash "-1232026034", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line nil, :hash "2030496455"} {:id "defn-/backup-path", :kind "defn-", :line 4, :end-line nil, :hash "-1243914595"} {:id "defn/save-backup!", :kind "defn", :line 8, :end-line nil, :hash "1537045573"} {:id "defn/restore-from-backup!", :kind "defn", :line 12, :end-line nil, :hash "2000402189"} {:id "defn/cleanup-backup!", :kind "defn", :line 20, :end-line nil, :hash "293297155"}]}
;; clj-mutate-manifest-end
