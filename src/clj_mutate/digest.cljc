(ns clj-mutate.digest
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(defn sha-256
  "Return a lowercase SHA-256 digest for text."
  [text]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str text) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

