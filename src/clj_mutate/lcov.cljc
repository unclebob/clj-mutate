(ns clj-mutate.lcov
  (:require [clojure.string :as str]))

(defn- parse-data-line [line]
  (let [[line-num count] (str/split (subs line 3) #",")]
    {:line-num (parse-long line-num)
     :count (parse-long count)}))

(defn- apply-lcov-line [{:keys [current-file result] :as state} line]
  (cond
    (str/starts-with? line "SF:")
    {:current-file (subs line 3)
     :result (assoc result (subs line 3) #{})}

    (str/starts-with? line "DA:")
    (let [{:keys [line-num count]} (parse-data-line line)]
      (if (and current-file (pos? count))
        {:current-file current-file
         :result (update result current-file conj line-num)}
        state))

    (= "end_of_record" line)
    (assoc state :current-file nil)

    :else
    state))

(defn parse-lcov
  "Parse LCOV text into {\"file-path\" #{covered-line-numbers}}."
  [lcov-content]
  (:result
    (reduce apply-lcov-line
            {:current-file nil :result {}}
            (str/split-lines lcov-content))))

(defn covered-lines
  "Return set of covered lines for source-path from lcov-map.
   Handles both exact and suffix matches."
  [lcov-map source-path]
  (or (get lcov-map source-path)
      (some (fn [[k v]] (when (str/ends-with? k source-path) v))
            lcov-map)))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-09-02T15:14:42.007173-05:00", :module-hash "1019752058", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line nil, :hash "-1034700927"} {:id "defn-/parse-data-line", :kind "defn-", :line 4, :end-line nil, :hash "1753012357"} {:id "defn-/apply-lcov-line", :kind "defn-", :line 9, :end-line nil, :hash "1226843982"} {:id "defn/parse-lcov", :kind "defn", :line 28, :end-line nil, :hash "-1778570802"} {:id "defn/covered-lines", :kind "defn", :line 36, :end-line nil, :hash "-1062448005"}]}
;; clj-mutate-manifest-end
