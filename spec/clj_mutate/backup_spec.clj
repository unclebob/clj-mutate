(ns clj-mutate.backup-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.backup :as backup]))

(describe "source backup"
  (it "saves, restores, and cleans up a backup file"
    (let [temp (java.io.File/createTempFile "backup-src" ".cljc")
          path (.getPath temp)]
      (spit path "(ns original)\n")
      (backup/save-backup! path "(ns backed-up)\n")
      (spit path "(ns mutated)\n")
      (should (backup/restore-from-backup! path))
      (should= "(ns backed-up)\n" (slurp path))
      (should-not (.exists (java.io.File. (str path ".mutation-backup"))))
      (backup/save-backup! path "(ns leftover)\n")
      (backup/cleanup-backup! path)
      (should-not (.exists (java.io.File. (str path ".mutation-backup"))))
      (.delete temp)))

  (it "returns nil when no backup exists"
    (let [temp (java.io.File/createTempFile "no-backup" ".cljc")
          path (.getPath temp)]
      (should-not (backup/restore-from-backup! path))
      (.delete temp))))

(run-specs)
