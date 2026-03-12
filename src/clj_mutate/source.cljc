(ns clj-mutate.source
  (:require [clojure.string :as str]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]
            [clj-mutate.mutations :as mutations]))

(defn read-source-forms
  [source-str]
  (let [rdr (reader-types/source-logging-push-back-reader source-str)
        opts {:read-cond :allow :features #{:clj} :eof ::eof}]
    (loop [forms []]
      (let [form (reader/read opts rdr)]
        (if (= ::eof form)
          forms
          (recur (conj forms form)))))))

(defn discover-all-mutations
  [forms]
  (vec (mapcat
         (fn [idx form]
           (map #(assoc % :form-index idx)
                (mutations/find-mutations form)))
         (range) forms)))

(defn partition-by-coverage
  [sites covered-lines]
  (if (nil? covered-lines)
    [sites []]
    (let [covered? #(or (nil? (:line %)) (contains? covered-lines (:line %)))
          grouped (group-by covered? sites)]
      [(vec (get grouped true [])) (vec (get grouped false []))])))

(defn token-pattern
  [token]
  (let [s (str token)]
    (or ({"="    (re-pattern "(?<![><=!])=(?!=)")
          "not=" (re-pattern "not=")
          ">"    (re-pattern ">(?!=)")
          ">="   (re-pattern ">=")
          "<"    (re-pattern "<(?!=)")
          "<="   (re-pattern "<=")} s)
        (when (re-matches #"\d+" s)
          (re-pattern (str "(?<!\\d|\\.)" (java.util.regex.Pattern/quote s) "(?!\\d|\\.)")))
        (when (re-matches #"[a-zA-Z].*" s)
          (re-pattern (str "(?<![a-zA-Z0-9_-])" (java.util.regex.Pattern/quote s) "(?![a-zA-Z0-9_-])")))
        (re-pattern (str "(?<=[\\s(])" (java.util.regex.Pattern/quote s) "(?=[\\s)])")))))

(defn mutate-source-text
  [original-content site]
  (let [lines (str/split original-content #"\n" -1)
        line-idx (dec (:line site))
        line (nth lines line-idx)
        pat (token-pattern (:original site))
        col (:column site)
        replaced (if col
                   (let [search-start (max 0 (- col 2))
                         prefix (subs line 0 search-start)
                         suffix (subs line search-start)
                         new-suffix (str/replace-first suffix pat (str (:mutant site)))]
                     (str prefix new-suffix))
                   (str/replace-first line pat (str (:mutant site))))
        new-lines (assoc lines line-idx replaced)]
    (str/join "\n" new-lines)))

;; clj-mutate-manifest-begin
;; {:version 1, :tested-at "2026-03-12T09:09:43.048792-05:00", :module-hash "-1616884661", :forms [{:id "form/0/ns", :kind "ns", :line 1, :end-line 5, :hash "-1894966203"} {:id "defn/read-source-forms", :kind "defn", :line 7, :end-line 15, :hash "-1905314524"} {:id "defn/discover-all-mutations", :kind "defn", :line 17, :end-line 23, :hash "1438022640"} {:id "defn/partition-by-coverage", :kind "defn", :line 25, :end-line 31, :hash "913852885"} {:id "defn/token-pattern", :kind "defn", :line 33, :end-line 46, :hash "1327827280"} {:id "defn/mutate-source-text", :kind "defn", :line 48, :end-line 63, :hash "556305298"}]}
;; clj-mutate-manifest-end
