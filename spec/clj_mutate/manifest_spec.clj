(ns clj-mutate.manifest-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.manifest :as manifest]
            [clj-mutate.source :as source]))

(describe "embedded manifest"
  (it "returns nil when no mutation metadata exists"
    (should= nil (manifest/extract-mutation-date "(ns foo)\n(defn bar [] 42)")))

  (it "extracts date from embedded manifest"
    (let [content (manifest/embed-mutation-manifest
                    "(ns foo)\n(defn bar [] 42)\n"
                    {:version 1
                     :tested-at "2026-02-22T10:15:30-06:00"
                     :module-hash "module-123"
                     :forms [{:id "defn/bar" :hash "123" :line 2 :end-line 2 :kind "defn"}]})]
      (should= "2026-02-22T10:15:30-06:00" (manifest/extract-mutation-date content))
      (should= {:version 1
                :tested-at "2026-02-22T10:15:30-06:00"
                :module-hash "module-123"
                :forms [{:id "defn/bar" :hash "123" :line 2 :end-line 2 :kind "defn"}]}
               (manifest/extract-embedded-manifest content))))

  (it "falls back to legacy top stamp"
    (should= "2026-02-22"
             (manifest/extract-mutation-date
               ";; mutation-tested: 2026-02-22\n(ns foo)\n(defn bar [] 42)")))

  (it "replaces an existing legacy top stamp"
    (should= ";; mutation-tested: 2026-03-12T09:30:00-05:00\n(ns foo)\n"
             (manifest/stamp-mutation-date
               ";; mutation-tested: 2026-02-22\n(ns foo)\n"
               "2026-03-12T09:30:00-05:00")))

  (it "strips legacy and embedded metadata before analysis"
    (let [content (str ";; mutation-tested: 2026-02-20\n"
                       "(ns foo)\n(defn bar [] 42)\n\n"
                       ";; clj-mutate-manifest-begin\n"
                       ";; {:version 1 :tested-at \"2026-02-22T10:15:30-06:00\" :module-hash \"module-123\" :forms []}\n"
                       ";; clj-mutate-manifest-end\n")]
      (should= "(ns foo)\n(defn bar [] 42)\n"
               (manifest/strip-mutation-metadata content))))

  (it "replaces an existing footer manifest"
    (let [original (manifest/embed-mutation-manifest
                     "(ns foo)\n(defn bar [] 42)\n"
                     {:version 1 :tested-at "2026-02-20T08:00:00-06:00" :module-hash "old-module" :forms []})
          updated (manifest/embed-mutation-manifest
                    original
                    {:version 1 :tested-at "2026-02-22T10:15:30-06:00" :module-hash "new-module" :forms [{:id "defn/bar" :hash "1"}]})]
      (should= "2026-02-22T10:15:30-06:00" (manifest/extract-mutation-date updated))
      (should-contain "clj-mutate-manifest-begin" updated)
      (should= 1 (count (re-seq #"clj-mutate-manifest-begin" updated))))))

(describe "top-level form manifest"
  (it "tracks top-level forms with ids, spans, and hashes"
    (let [source "(ns foo)\n(defn bar [] 42)\n(defmethod quux :x [] true)\n"
          form-manifest (manifest/top-level-form-manifest source)]
      (should= "form/0/ns" (:id (first form-manifest)))
      (should= "defn/bar" (:id (second form-manifest)))
      (should= "defmethod/quux/:x" (:id (nth form-manifest 2)))
      (should= 2 (:line (second form-manifest)))
      (should (contains? (second form-manifest) :end-line))
      (should (let [end-line (:end-line (second form-manifest))]
                (or (nil? end-line) (pos-int? end-line))))
      (should (re-matches #"[0-9a-f]{64}" (:hash (second form-manifest))))))

  (it "computes a stable SHA-256 module hash from source slices"
    (let [a "(ns foo)\n(defn bar [] #(inc %))\n"
          b "(ns foo)\r\n(defn bar [] #(inc %))\r\n"
          c "(ns foo)\n(defn bar [] #(dec %))\n"]
      (should= (manifest/module-hash a) (manifest/module-hash b))
      (should-not= (manifest/module-hash a) (manifest/module-hash c))
      (should (re-matches #"[0-9a-f]{64}" (manifest/module-hash a)))))

  (it "assigns unique ids to repeated named top-level forms"
    (let [forms (manifest/top-level-form-manifest
                  "(defn f [] 1)\n(defn f [] 2)\n")]
      (should= ["defn/f" "defn/f#2"] (mapv :id forms))))

  (it "marks version-2 verified manifests with matching provenance as trusted"
    (let [provenance {:mutation-rules-version "2" :test-profile "abc"}
          current (manifest/build-embedded-manifest
                    "(ns foo)\n" "2026-09-03T10:00:00-05:00"
                    {:verified? true :provenance provenance})
          unverified (assoc current :verified? false)
          version-one {:version 1 :module-hash "old"}]
      (should (manifest/current-manifest? current))
      (should (manifest/trusted-manifest? current provenance))
      (should-not (manifest/trusted-manifest? current {:test-profile "changed"}))
      (should-not (manifest/trusted-manifest? unverified provenance))
      (should-not (manifest/current-manifest? version-one))))

  (it "finds changed top-level form indices from a prior manifest"
    (let [forms "(ns foo)\n(defn unchanged [] 1)\n(defn changed [] 3)\n"
          prior {:version 1
                 :tested-at "2026-02-22T10:15:30-06:00"
                 :module-hash "old-module"
                 :forms [{:id "form/0/ns" :hash (:hash (first (manifest/top-level-form-manifest forms)))}
                         {:id "defn/unchanged" :hash (:hash (second (manifest/top-level-form-manifest forms)))}
                         {:id "defn/changed" :hash "old-hash"}]}]
      (should= #{2} (manifest/changed-form-indices forms prior)))))

(run-specs)
