(ns clj-mutate.syntax
  (:require [clojure.string :as str]
            [rewrite-clj.zip :as z]))

(def zipper-options {:track-position? true})

(defn normalize-newlines
  [source]
  (str/replace source #"\r\n?" "\n"))

(defn of-source
  [source]
  (z/of-string* (normalize-newlines source) zipper-options))

(defn sexpr
  [zloc]
  (when zloc
    (try
      (z/sexpr zloc)
      (catch Exception _ nil))))

(defn top-level-locations
  [root]
  (loop [loc (z/down root)
         result []]
    (if loc
      (recur (z/right loc) (conj result loc))
      result)))

(defn semantic-path
  "Return child indexes from the zipper root to zloc. Whitespace and comments
   are ignored by rewrite-clj's non-star navigation functions."
  [zloc]
  (loop [loc zloc
         result ()]
    (if-let [parent (z/up loc)]
      (let [child-index (loop [sibling loc
                               index 0]
                          (if-let [left (z/left sibling)]
                            (recur left (inc index))
                            index))]
        (recur parent (conj result child-index)))
      (vec result))))

(defn location-at-path
  [root path]
  (reduce
    (fn [loc child-index]
      (when loc
        (loop [child (z/down loc)
               index child-index]
          (cond
            (nil? child) nil
            (zero? index) child
            :else (recur (z/right child) (dec index))))))
    root
    path))

(defn position
  [zloc]
  (let [[line column] (z/position zloc)]
    {:line line :column column}))

(defn source
  [zloc]
  (z/string zloc))

(defn replace-source
  [zloc replacement]
  (-> zloc
      (z/replace replacement)
      z/root-string))

(defn form-kind
  [form]
  (when (seq? form)
    (first form)))

(defn top-level-form-id
  [index form]
  (let [head (form-kind form)]
    (cond
      (and (#{'def 'defn 'defn- 'defmacro 'defmulti} head)
           (symbol? (second form)))
      (str head "/" (second form))

      (and (= 'defmethod head)
           (symbol? (second form)))
      (str head "/" (second form) "/" (pr-str (nth form 2 nil)))

      :else
      (str "form/" index "/" (or head :literal)))))

(defn end-line
  [zloc]
  (let [{:keys [line]} (position zloc)]
    (+ line (count (re-seq #"\n" (source zloc))))))

