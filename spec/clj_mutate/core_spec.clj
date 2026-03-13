(ns clj-mutate.core-spec
  (:require [speclj.core :refer :all]
            [clj-mutate.core :as core]
            [clj-mutate.coverage :as coverage]
            [clj-mutate.manifest :as manifest]
            [clj-mutate.runner :as runner]
            [clj-mutate.workers :as workers]))

(describe "read-source-forms"
  (it "reads Clojure forms from a string"
    (let [forms (core/read-source-forms "(ns foo) (defn bar [] 42)")]
      (should= 2 (count forms))
      (should= 'ns (first (first forms))))))

(describe "discover-all-mutations"
  (it "finds mutations across multiple forms"
    (let [forms (core/read-source-forms "(defn foo [] (+ 1 2)) (defn bar [] (> x 0))")
          sites (core/discover-all-mutations forms)]
      (should (some #(= (:original %) '+) sites))
      (should (some #(= (:original %) '>) sites))
      (should (some #(= (:original %) 1) sites)))))

(describe "mutate-source-text"
  (it "preserves comments and indentation"
    (let [src "(ns foo)\n;; a comment\n(defn bar [] (+ 1 2))\n"
          forms (core/read-source-forms src)
          sites (core/discover-all-mutations forms)
          plus-site (first (filter #(= (:original %) '+) sites))
          result (core/mutate-source-text src plus-site)]
      (should-contain ";; a comment" result)
      (should-contain "(- 1 2)" result)))

  (it "replaces only the targeted token"
    (let [src "(defn f [] (+ 1 (+ 2 3)))\n"
          forms (core/read-source-forms src)
          sites (core/discover-all-mutations forms)
          first-plus (first (filter #(= (:original %) '+) sites))
          result (core/mutate-source-text src first-plus)]
      (should-contain "(- 1 (+ 2 3))" result)))

  (it "= does not match inside not="
    (let [src "(defn f [] (not= x y))\n"
          forms (core/read-source-forms src)
          sites (core/discover-all-mutations forms)
          eq-sites (filter #(and (= (:original %) '=) (= (:mutant %) 'not=)) sites)]
      (should= 0 (count eq-sites))))

  (it "0 does not match inside 10"
    (let [src "(defn f [] (+ 10 x))\n"
          forms (core/read-source-forms src)
          sites (core/discover-all-mutations forms)
          zero-sites (filter #(and (= (:original %) 0) (= (:mutant %) 1)) sites)]
      (should= 0 (count zero-sites))))

  (it "preserves trailing newline"
    (let [src "(defn f [] (+ 1 2))\n"
          forms (core/read-source-forms src)
          sites (core/discover-all-mutations forms)
          plus-site (first (filter #(= (:original %) '+) sites))
          result (core/mutate-source-text src plus-site)]
      (should (.endsWith result "\n")))))

(describe "mutate-and-test"
  (it "writes mutated file, runs specs, restores original"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          original-content "(ns test-ns)\n(defn foo [] (+ 1 2))\n"]
      (spit temp-path original-content)
      (with-redefs [runner/run-specs (fn [& _] :killed)]
        (let [forms (core/read-source-forms original-content)
              sites (core/discover-all-mutations forms)
              plus-site (first (filter #(= (:original %) '+) sites))
              result (core/mutate-and-test temp-path original-content
                                           forms plus-site 30000 "clj -M:spec")]
          (should= :killed (:result result))
          (should= original-content (slurp temp-path))))
      (.delete temp-file))))

(describe "validate-args"
  (it "returns error when no args given"
    (let [result (core/validate-args [])]
      (should-contain :error result)))

  (it "returns help when --help is provided"
    (let [result (core/validate-args ["--help"])]
      (should= true (:help result))
      (should-contain "Usage:" (:usage result))))

  (it "returns error when source file doesn't exist"
    (let [result (core/validate-args ["nonexistent.cljc"])]
      (should-contain :error result)))

  (it "returns an error for unknown options"
    (let [temp (java.io.File/createTempFile "src" ".cljc")]
      (spit temp "(ns test-ns)")
      (let [result (core/validate-args [(.getPath temp) "--bogus"])]
        (should= "Unknown option: --bogus" (:error result))
        (.delete temp))))

  (it "returns an error for unexpected extra arguments"
    (let [temp (java.io.File/createTempFile "src" ".cljc")]
      (spit temp "(ns test-ns)")
      (let [result (core/validate-args [(.getPath temp) "extra.cljc"])]
        (should= "Unexpected extra argument: extra.cljc" (:error result))
        (.delete temp))))

  (it "returns source-path when file exists"
    (let [temp (java.io.File/createTempFile "src" ".cljc")]
      (spit temp "(ns test-ns)")
      (let [result (core/validate-args [(.getPath temp)])]
        (should= (.getPath temp) (:source-path result))
        (should= 10 (:timeout-factor result))
        (should= "clj -M:spec --tag ~no-mutate" (:test-command result))
        (should= false (:since-last-run result))
        (should= false (:mutate-all result))
        (should= 50 (:mutation-warning result))
        (should= nil (:max-workers result))
        (.delete temp))))

  (it "parses --since-last-run"
    (let [temp (java.io.File/createTempFile "src" ".cljc")]
      (spit temp "(ns test-ns)")
      (let [result (core/validate-args [(.getPath temp) "--since-last-run"])]
        (should= true (:since-last-run result))
        (.delete temp))))

  (it "parses --mutate-all"
    (let [temp (java.io.File/createTempFile "src" ".cljc")]
      (spit temp "(ns test-ns)")
      (let [result (core/validate-args [(.getPath temp) "--mutate-all"])]
        (should= true (:mutate-all result))
        (.delete temp))))

  (it "parses --scan"
    (let [temp (java.io.File/createTempFile "src" ".cljc")]
      (spit temp "(ns test-ns)")
      (let [result (core/validate-args [(.getPath temp) "--scan"])]
        (should= true (:scan result))
        (.delete temp))))

  (it "parses --update-manifest"
    (let [temp (java.io.File/createTempFile "src" ".cljc")]
      (spit temp "(ns test-ns)")
      (let [result (core/validate-args [(.getPath temp) "--update-manifest"])]
        (should= true (:update-manifest result))
        (.delete temp))))

  (it "parses --mutation-warning"
    (let [temp (java.io.File/createTempFile "src" ".cljc")]
      (spit temp "(ns test-ns)")
      (let [result (core/validate-args [(.getPath temp) "--mutation-warning" "75"])]
        (should= 75 (:mutation-warning result))
        (.delete temp))))

  (it "returns errors for missing option values"
    (let [temp (java.io.File/createTempFile "src" ".cljc")]
      (spit temp "(ns test-ns)")
      (should= "Missing value for --lines."
               (:error (core/validate-args [(.getPath temp) "--lines"])))
      (should= "Missing value for --timeout-factor."
               (:error (core/validate-args [(.getPath temp) "--timeout-factor"])))
      (should= "Missing value for --test-command."
               (:error (core/validate-args [(.getPath temp) "--test-command"])))
      (should= "Missing value for --max-workers."
               (:error (core/validate-args [(.getPath temp) "--max-workers"])))
      (should= "Missing value for --mutation-warning."
               (:error (core/validate-args [(.getPath temp) "--mutation-warning"])))
      (.delete temp)))

  (it "rejects combining --lines with --since-last-run in either order"
    (let [temp (java.io.File/createTempFile "src" ".cljc")]
      (spit temp "(ns test-ns)")
      (should-contain :error
                      (core/validate-args [(.getPath temp) "--lines" "3" "--since-last-run"]))
      (should-contain :error
                      (core/validate-args [(.getPath temp) "--since-last-run" "--lines" "3"]))
      (.delete temp)))

  (it "rejects combining --mutate-all with --lines or --since-last-run"
    (let [temp (java.io.File/createTempFile "src" ".cljc")]
      (spit temp "(ns test-ns)")
      (should-contain :error
                      (core/validate-args [(.getPath temp) "--mutate-all" "--lines" "3"]))
      (should-contain :error
                      (core/validate-args [(.getPath temp) "--since-last-run" "--mutate-all"]))
      (.delete temp)))

  (it "rejects combining --scan with mutation execution options"
    (let [temp (java.io.File/createTempFile "src" ".cljc")]
      (spit temp "(ns test-ns)")
      (should-contain :error
                      (core/validate-args [(.getPath temp) "--scan" "--lines" "3"]))
      (should-contain :error
                      (core/validate-args [(.getPath temp) "--scan" "--since-last-run"]))
      (should-contain :error
                      (core/validate-args [(.getPath temp) "--scan" "--mutate-all"]))
      (should-contain :error
                      (core/validate-args [(.getPath temp) "--scan" "--timeout-factor" "7"]))
      (should-contain :error
                      (core/validate-args [(.getPath temp) "--scan" "--test-command" "clj -M:all-tests"]))
      (should-contain :error
                      (core/validate-args [(.getPath temp) "--scan" "--max-workers" "2"]))
      (.delete temp)))

  (it "rejects combining --update-manifest with mutation execution options"
    (let [temp (java.io.File/createTempFile "src" ".cljc")]
      (spit temp "(ns test-ns)")
      (should-contain :error
                      (core/validate-args [(.getPath temp) "--update-manifest" "--scan"]))
      (should-contain :error
                      (core/validate-args [(.getPath temp) "--update-manifest" "--lines" "3"]))
      (should-contain :error
                      (core/validate-args [(.getPath temp) "--update-manifest" "--since-last-run"]))
      (should-contain :error
                      (core/validate-args [(.getPath temp) "--update-manifest" "--mutate-all"]))
      (should-contain :error
                      (core/validate-args [(.getPath temp) "--update-manifest" "--timeout-factor" "7"]))
      (should-contain :error
                      (core/validate-args [(.getPath temp) "--update-manifest" "--test-command" "clj -M:all-tests"]))
      (should-contain :error
                      (core/validate-args [(.getPath temp) "--update-manifest" "--max-workers" "2"]))
      (.delete temp)))

  (it "parses --timeout-factor as a positive integer"
    (let [temp (java.io.File/createTempFile "src" ".cljc")]
      (spit temp "(ns test-ns)")
      (let [result (core/validate-args [(.getPath temp) "--timeout-factor" "7"])]
        (should= 7 (:timeout-factor result))
        (.delete temp))))

  (it "returns an error for non-positive --timeout-factor"
    (let [temp (java.io.File/createTempFile "src" ".cljc")]
      (spit temp "(ns test-ns)")
      (let [result (core/validate-args [(.getPath temp) "--timeout-factor" "0"])]
        (should-contain :error result)
        (.delete temp))))

  (it "parses --test-command"
    (let [temp (java.io.File/createTempFile "src" ".cljc")]
      (spit temp "(ns test-ns)")
      (let [result (core/validate-args [(.getPath temp) "--test-command" "clj -M:all-tests"])]
        (should= "clj -M:all-tests" (:test-command result))
        (.delete temp))))

  (it "returns an error for blank --test-command"
    (let [temp (java.io.File/createTempFile "src" ".cljc")]
      (spit temp "(ns test-ns)")
      (let [result (core/validate-args [(.getPath temp) "--test-command" "   "])]
        (should= "Missing value for --test-command." (:error result))
        (.delete temp))))

  (it "parses --max-workers as a positive integer"
    (let [temp (java.io.File/createTempFile "src" ".cljc")]
      (spit temp "(ns test-ns)")
      (let [result (core/validate-args [(.getPath temp) "--max-workers" "3"])]
        (should= 3 (:max-workers result))
        (.delete temp))))

  (it "returns an error for non-positive --max-workers"
    (let [temp (java.io.File/createTempFile "src" ".cljc")]
      (spit temp "(ns test-ns)")
      (let [result (core/validate-args [(.getPath temp) "--max-workers" "0"])]
        (should-contain :error result)
        (.delete temp)))))

  (it "returns an error for non-positive --mutation-warning"
    (let [temp (java.io.File/createTempFile "src" ".cljc")]
      (spit temp "(ns test-ns)")
      (let [result (core/validate-args [(.getPath temp) "--mutation-warning" "0"])]
        (should-contain :error result)
        (.delete temp))))

(describe "partition-by-coverage"
  (it "separates covered from uncovered sites"
    (let [sites [{:line 1 :original '+} {:line 2 :original '-} {:line 3 :original '>}]
          covered-lines #{1 3}
          [covered uncovered] (core/partition-by-coverage sites covered-lines)]
      (should= 2 (count covered))
      (should= 1 (count uncovered))
      (should= 2 (:line (first uncovered)))))

  (it "treats nil-line sites as covered"
    (let [sites [{:line nil :original '+} {:line 5 :original '-}]
          covered-lines #{5}
          [covered uncovered] (core/partition-by-coverage sites covered-lines)]
      (should= 2 (count covered))
      (should= 0 (count uncovered))))

  (it "treats all sites as covered when coverage is nil"
    (let [sites [{:line 1 :original '+} {:line 2 :original '-}]
          [covered uncovered] (core/partition-by-coverage sites nil)]
      (should= 2 (count covered))
      (should= 0 (count uncovered)))))

(describe "format-report"
  (it "produces summary with kill count"
    (let [results [{:site {:description "+ -> -"} :result :killed}
                   {:site {:description "1 -> 0"} :result :survived}]
          report (core/format-report "src/empire/foo.cljc" results 0)]
      (should-contain "1/2 mutants killed" report)
      (should-contain "SURVIVED" report)
      (should-contain "KILLED" report)))

  (it "includes line numbers in progress lines"
    (let [results [{:site {:description "+ -> -" :line 42} :result :killed}
                   {:site {:description "1 -> 0" :line 99 :index 1} :result :survived}]
          report (core/format-report "src/empire/foo.cljc" results 0)]
      (should-contain "L42" report)
      (should-contain "L99" report)))

  (it "includes line numbers in survivor summary"
    (let [results [{:site {:description "1 -> 0" :line 207 :index 0} :result :survived}]
          report (core/format-report "src/empire/foo.cljc" results 0)]
      (should-contain "L207" report)))

  (it "includes uncovered count in summary"
    (let [results [{:site {:description "+ -> -"} :result :killed}]
          report (core/format-report "src/empire/foo.cljc" results 3)]
      (should-contain "1/1 mutants killed" report)
      (should-contain "3 uncovered" report))))

(describe "embedded manifest"
  (it "returns nil when no mutation metadata exists"
    (should= nil (core/extract-mutation-date "(ns foo)\n(defn bar [] 42)")))

  (it "extracts date from embedded manifest"
    (let [content (core/embed-mutation-manifest
                    "(ns foo)\n(defn bar [] 42)\n"
                    {:version 1
                     :tested-at "2026-02-22T10:15:30-06:00"
                     :module-hash "module-123"
                     :forms [{:id "defn/bar" :hash "123" :line 2 :end-line 2 :kind "defn"}]})]
      (should= "2026-02-22T10:15:30-06:00" (core/extract-mutation-date content))
      (should= {:version 1
                :tested-at "2026-02-22T10:15:30-06:00"
                :module-hash "module-123"
                :forms [{:id "defn/bar" :hash "123" :line 2 :end-line 2 :kind "defn"}]}
               (core/extract-embedded-manifest content))))

  (it "falls back to legacy top stamp"
    (should= "2026-02-22"
             (core/extract-mutation-date
               ";; mutation-tested: 2026-02-22\n(ns foo)\n(defn bar [] 42)")))

  (it "replaces an existing legacy top stamp"
    (should= ";; mutation-tested: 2026-03-12T09:30:00-05:00\n(ns foo)\n"
             (core/stamp-mutation-date
               ";; mutation-tested: 2026-02-22\n(ns foo)\n"
               "2026-03-12T09:30:00-05:00")))

  (it "strips legacy and embedded metadata before analysis"
    (let [content (str ";; mutation-tested: 2026-02-20\n"
                       "(ns foo)\n(defn bar [] 42)\n\n"
                       ";; clj-mutate-manifest-begin\n"
                       ";; {:version 1 :tested-at \"2026-02-22T10:15:30-06:00\" :module-hash \"module-123\" :forms []}\n"
                       ";; clj-mutate-manifest-end\n")]
      (should= "(ns foo)\n(defn bar [] 42)\n"
               (core/strip-mutation-metadata content))))

  (it "replaces an existing footer manifest"
    (let [original (core/embed-mutation-manifest
                     "(ns foo)\n(defn bar [] 42)\n"
                     {:version 1 :tested-at "2026-02-20T08:00:00-06:00" :module-hash "old-module" :forms []})
          updated (core/embed-mutation-manifest
                    original
                    {:version 1 :tested-at "2026-02-22T10:15:30-06:00" :module-hash "new-module" :forms [{:id "defn/bar" :hash "1"}]})]
      (should= "2026-02-22T10:15:30-06:00" (core/extract-mutation-date updated))
      (should-contain "clj-mutate-manifest-begin" updated)
      (should= 1 (count (re-seq #"clj-mutate-manifest-begin" updated))))))

(describe "top-level form manifest"
  (it "tracks top-level forms with ids, spans, and hashes"
    (let [forms (core/read-source-forms "(ns foo)\n(defn bar [] 42)\n(defmethod quux :x [] true)\n")
          manifest (core/top-level-form-manifest forms)]
      (should= "form/0/ns" (:id (first manifest)))
      (should= "defn/bar" (:id (second manifest)))
      (should= "defmethod/quux/:x" (:id (nth manifest 2)))
      (should= 2 (:line (second manifest)))
      (should= 2 (:end-line (second manifest)))
      (should-not-be-nil (:hash (second manifest)))))

  (it "computes a semantic module hash from parsed forms"
    (let [a (core/read-source-forms "(ns foo)\n(defn bar [] 42)\n")
          b (core/read-source-forms "(ns foo)\n(defn bar [] 42)\n")
          c (core/read-source-forms "(ns foo)\n(defn bar [] 43)\n")]
      (should= (core/module-hash a) (core/module-hash b))
      (should-not= (core/module-hash a) (core/module-hash c))))

  (it "finds changed top-level form indices from a prior manifest"
    (let [forms (core/read-source-forms "(ns foo)\n(defn unchanged [] 1)\n(defn changed [] 3)\n")
          prior {:version 1
                 :tested-at "2026-02-22T10:15:30-06:00"
                 :module-hash "old-module"
                 :forms [{:id "form/0/ns" :hash (:hash (first (core/top-level-form-manifest forms)))}
                         {:id "defn/unchanged" :hash (:hash (second (core/top-level-form-manifest forms)))}
                         {:id "defn/changed" :hash "old-hash"}]}]
      (should= #{2} (core/changed-form-indices forms prior)))))

(describe "run-mutation-testing embeds manifest"
  (tags :no-mutate)

  (it "writes the footer manifest after a full run"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          original "(ns test-ns)\n(defn foo [] (+ 1 2))\n"]
      (spit temp-path original)
      (with-redefs [runner/run-specs (fn [& _] :killed)
                    runner/run-specs-timed (fn [cmd]
                                             (should= "clj -M:spec --tag ~no-mutate" cmd)
                                             {:result :survived :elapsed-ms 100})
                    coverage/load-coverage (fn [_] nil)
                    core/run-mutations-parallel
                    (fn [sites source-path content timeout-ms max-workers test-command]
                      (should= nil max-workers)
                      (should= "clj -M:spec --tag ~no-mutate" test-command)
                      (doall (map (fn [site]
                                    (core/mutate-and-test source-path content nil site timeout-ms test-command))
                                  sites)))]
        (core/run-mutation-testing temp-path)
        (let [updated (slurp temp-path)]
          (should-not-be-nil (core/extract-embedded-manifest updated))
          (should= (core/module-hash (core/read-source-forms (core/strip-mutation-metadata updated)))
                   (:module-hash (core/extract-embedded-manifest updated)))
          (should-contain "clj-mutate-manifest-begin" updated)))
      (.delete temp-file)))

  (it "reports previous mutation test date"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          original (core/embed-mutation-manifest
                     "(ns test-ns)\n(defn foo [] (+ 1 2))\n"
                     {:version 1
                      :tested-at "2026-01-15T09:30:00-06:00"
                      :module-hash "module-123"
                      :forms [{:id "form/0/ns" :hash "ns"}
                              {:id "defn/foo" :hash "foo"}]})]
      (spit temp-path original)
      (with-redefs [runner/run-specs (fn [& _] :killed)
                    runner/run-specs-timed (fn [cmd]
                                             (should= "clj -M:spec --tag ~no-mutate" cmd)
                                             {:result :survived :elapsed-ms 100})
                    coverage/load-coverage (fn [_] nil)
                    core/run-mutations-parallel
                    (fn [sites source-path content timeout-ms max-workers test-command]
                      (should= nil max-workers)
                      (should= "clj -M:spec --tag ~no-mutate" test-command)
                      (doall (map (fn [site]
                                    (core/mutate-and-test source-path content nil site timeout-ms test-command))
                                  sites)))]
                        (let [captured (with-out-str
                                         (core/run-mutation-testing temp-path))]
          (should-contain "Previous mutation test: 2026-01-15T09:30:00-06:00" captured)))
      (.delete temp-file))))

  (it "filters to changed top-level forms with --since-last-run"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          initial "(ns test-ns)\n(defn unchanged [] (+ 1 2))\n(defn changed [] (+ 3 4))\n"
          updated "(ns test-ns)\n(defn unchanged [] (+ 1 2))\n(defn changed [] (+ 30 4))\n(defn added [] (+ 5 6))\n"
          prior-manifest (core/build-embedded-manifest (core/read-source-forms initial) "2026-02-20T08:00:00-06:00")
          source-with-manifest (core/embed-mutation-manifest initial prior-manifest)
          captured-sites (atom nil)]
      (spit temp-path source-with-manifest)
      (spit temp-path updated)
      (spit temp-path (core/embed-mutation-manifest updated prior-manifest))
      (with-redefs [runner/run-specs (fn [& _] :killed)
                    runner/run-specs-timed (fn [_] {:result :survived :elapsed-ms 100})
                    coverage/load-coverage (fn [_] nil)
                    core/run-mutations-parallel
                    (fn [sites _ _ _ _ _]
                      (reset! captured-sites sites)
                      (mapv (fn [site] {:site site :result :killed :timeout? false}) sites))]
        (let [output (with-out-str
                       (core/run-mutation-testing temp-path nil 10 "clj -M:spec" nil true))]
          (should-contain "Change surface area: 2 mutations in new top-level forms" output)
          (should-contain "Change surface area: 2 mutations in manifest-violating top-level forms" output))
        (should (seq @captured-sites))
        (should= #{2 3} (set (map :form-index @captured-sites)))
        (should (re-find #"\d{4}-\d{2}-\d{2}T" (:tested-at (core/extract-embedded-manifest (slurp temp-path))))))
      (.delete temp-file)))

  (it "short-circuits --since-last-run when the module hash is unchanged"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          source "(ns test-ns)\n(defn unchanged [] (+ 1 2))\n"
          prior-manifest (core/build-embedded-manifest (core/read-source-forms source) "2026-02-20T08:00:00-06:00")
          source-with-manifest (core/embed-mutation-manifest source prior-manifest)
          called? (atom false)]
      (spit temp-path source-with-manifest)
      (with-redefs [runner/run-specs (fn [& _] :killed)
                    runner/run-specs-timed (fn [_] {:result :survived :elapsed-ms 100})
                    coverage/load-coverage (fn [_] nil)
                    core/run-mutations-parallel
                    (fn [& _]
                      (reset! called? true)
                      [])]
        (let [output (with-out-str
                       (core/run-mutation-testing temp-path nil 10 "clj -M:spec" nil true))]
          (should= false @called?)
          (should-contain "Module hash unchanged; no mutations to test." output)
          (should-contain "Change surface area: 0 mutations in new top-level forms" output)
          (should-contain "Change surface area: 0 mutations in manifest-violating top-level forms" output)))
      (.delete temp-file)))

  (it "defaults to differential mutation when a manifest exists"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          initial "(ns test-ns)\n(defn unchanged [] (+ 1 2))\n(defn changed [] (+ 3 4))\n"
          updated "(ns test-ns)\n(defn unchanged [] (+ 1 2))\n(defn changed [] (+ 30 4))\n"
          prior-manifest (core/build-embedded-manifest (core/read-source-forms initial) "2026-02-20T08:00:00-06:00")
          captured-sites (atom nil)]
      (spit temp-path (core/embed-mutation-manifest updated prior-manifest))
      (with-redefs [runner/run-specs (fn [& _] :killed)
                    runner/run-specs-timed (fn [_] {:result :survived :elapsed-ms 100})
                    coverage/load-coverage (fn [_] nil)
                    core/run-mutations-parallel
                    (fn [sites _ _ _ _ _]
                      (reset! captured-sites sites)
                      (mapv (fn [site] {:site site :result :killed :timeout? false}) sites))]
        (let [output (with-out-str (core/run-mutation-testing temp-path))]
          (should (seq @captured-sites))
          (should (every? #(= 2 (:form-index %)) @captured-sites))
          (should-contain "Filtering to changed top-level forms" output)))
      (.delete temp-file)))

  (it "uses --mutate-all to override default differential mutation"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          source "(ns test-ns)\n(defn foo [] (+ 1 2))\n"
          prior-manifest (core/build-embedded-manifest (core/read-source-forms source) "2026-02-20T08:00:00-06:00")
          captured-sites (atom nil)]
      (spit temp-path (core/embed-mutation-manifest source prior-manifest))
      (with-redefs [runner/run-specs (fn [& _] :killed)
                    runner/run-specs-timed (fn [_] {:result :survived :elapsed-ms 100})
                    coverage/load-coverage (fn [_] nil)
                    core/run-mutations-parallel
                    (fn [sites _ _ _ _ _]
                      (reset! captured-sites sites)
                      (mapv (fn [site] {:site site :result :killed :timeout? false}) sites))]
        (let [output (with-out-str
                       (core/run-mutation-testing temp-path nil 10 "clj -M:spec" nil false true 50))]
          (should= 2 (count @captured-sites))
          (should-not (re-find #"changed top-level forms" output))))
      (.delete temp-file)))

  (it "prints a warning when mutation count exceeds the threshold"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          source "(ns test-ns)\n(defn foo [] (+ 1 2))\n"]
      (spit temp-path source)
      (with-redefs [runner/run-specs (fn [& _] :killed)
                    runner/run-specs-timed (fn [_] {:result :survived :elapsed-ms 100})
                    coverage/load-coverage (fn [_] nil)
                    core/run-mutations-parallel
                    (fn [sites _ _ _ _ _]
                      (mapv (fn [site] {:site site :result :killed :timeout? false}) sites))]
        (let [output (with-out-str
                       (core/run-mutation-testing temp-path nil 10 "clj -M:spec" nil false false 1))]
          (should-contain "WARNING: Found 2 mutations. Consider splitting this module." output)))
      (.delete temp-file)))

(describe "run-mutation-testing options"
  (it "uses timeout-factor and test-command"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          original "(ns test-ns)\n(defn foo [] (+ 1 2))\n"
          captured-timeout (atom nil)
          captured-command (atom nil)]
      (spit temp-path original)
      (with-redefs [runner/run-specs (fn [& _] :killed)
                    runner/run-specs-timed (fn [cmd]
                                             (reset! captured-command cmd)
                                             {:result :survived :elapsed-ms 200})
                    coverage/load-coverage (fn [_] nil)
                    core/run-mutations-parallel
                    (fn [sites _ _ timeout-ms _ test-command]
                      (reset! captured-timeout timeout-ms)
                      (reset! captured-command test-command)
                      (mapv (fn [site] {:site site :result :killed :timeout? false}) sites))]
        (core/run-mutation-testing temp-path nil 3 "clj -M:all-tests" nil)
        (should= 600 @captured-timeout)
        (should= "clj -M:all-tests" @captured-command))
      (.delete temp-file))))

(describe "print-uncovered"
  (it "prints coverage gaps when uncovered mutations exist"
    (let [output (with-out-str
                   (#'core/print-uncovered [{:line 12 :description "if -> if-not"}
                                            {:line 15 :description "0 -> 1"}]))]
      (should-contain "=== Coverage Gaps (2 mutations on uncovered lines) ===" output)
      (should-contain "line 12: if -> if-not" output)
      (should-contain "line 15: 0 -> 1" output))))

(describe "run-mutation-testing arities"
  (it "accepts a since-last-run argument without requiring mutate-all and mutation-warning"
    (let [captured (atom nil)]
      (with-redefs [core/restore-from-backup! (fn [_] false)
                    core/extract-embedded-manifest (fn [_] nil)
                    core/mutation-run-context
                    (fn [_ _]
                      {:prev-date nil
                       :prior-manifest nil
                       :analysis-content "(ns test-ns)\n"
                       :all-sites []
                       :covered-sites []
                       :uncovered []
                       :module-unchanged? false
                       :changed-forms #{}
                       :manifest-content "(ns test-ns)\n"})
                    core/select-mutation-sites (fn [& _] [])
                    core/print-run-header (fn [& _] nil)
                    core/with-baseline (fn [test-command timeout-factor on-pass]
                                         (reset! captured {:test-command test-command
                                                           :timeout-factor timeout-factor})
                                         (on-pass 100))
                    core/print-uncovered (fn [_] nil)
                    core/save-backup! (fn [& _] nil)
                    core/run-mutation-suite (fn [& _] [])
                    core/summarize-results (fn [& _] nil)
                    core/cleanup-backup! (fn [& _] nil)
                    spit (fn [& _] nil)
                    slurp (fn [_] "(ns test-ns)\n")]
        (core/run-mutation-testing "src/test.cljc" nil 7 "clj -M:custom" nil true)
        (should= {:test-command "clj -M:custom"
                  :timeout-factor 7}
                 @captured)))))

(describe "scan-mutation-sites"
  (it "reports total and changed mutation sites with a warning"
    (let [source "(ns test-ns)\n(defn foo [] (+ 1 2))\n"
          prior (core/build-embedded-manifest (core/read-source-forms source)
                                              "2026-02-20T08:00:00-06:00")
          updated "(ns test-ns)\n(defn foo [] (+ 1 20))\n"
          content (core/embed-mutation-manifest updated prior)
          output (with-out-str
                   (with-redefs [slurp (fn [_] content)]
                     (#'core/scan-mutation-sites "src/test.cljc" 1)))]
      (should-contain "=== Mutation Scan: src/test.cljc ===" output)
      (should-contain "Found 2 mutation sites." output)
      (should-contain "Changed mutation sites: 2" output)
      (should-contain "WARNING: Found 2 mutations. Consider splitting this module." output)))

  (it "reports zero changed mutation sites when the module hash is unchanged"
    (let [source "(ns test-ns)\n(defn foo [] (+ 1 2))\n"
          prior (core/build-embedded-manifest (core/read-source-forms source)
                                              "2026-02-20T08:00:00-06:00")
          content (core/embed-mutation-manifest source prior)
          output (with-out-str
                   (with-redefs [slurp (fn [_] content)]
                     (#'core/scan-mutation-sites "src/test.cljc" 50)))]
      (should-contain "Found 2 mutation sites." output)
      (should-contain "Changed mutation sites: 0" output))))

(describe "update-manifest!"
  (it "rewrites the embedded manifest for the current file content"
    (let [temp-file (java.io.File/createTempFile "manifest" ".cljc")
          temp-path (.getPath temp-file)
          original "(ns test-ns)\n(defn foo [] (+ 1 2))\n"
          prior (core/build-embedded-manifest (core/read-source-forms original)
                                              "2026-02-20T08:00:00-06:00")
          stamped (core/embed-mutation-manifest "(ns test-ns)\n(defn foo [] (+ 1 20))\n" prior)]
      (spit temp-path stamped)
      (with-redefs [manifest/now-str (fn [] "2026-03-12T12:00:00-05:00")]
        (#'core/update-manifest! temp-path))
      (let [updated (slurp temp-path)
            embedded (core/extract-embedded-manifest updated)
            analysis-content (core/strip-mutation-metadata updated)
            forms (core/read-source-forms analysis-content)]
        (should= "2026-03-12T12:00:00-05:00" (:tested-at embedded))
        (should= (core/module-hash forms) (:module-hash embedded))
        (should= (core/top-level-form-manifest forms) (:forms embedded)))
      (.delete temp-file))))

(describe "handle-main-result"
  (it "prints help without exiting"
    (let [output (with-out-str (#'core/handle-main-result {:help true :usage "Usage text"}))]
      (should-contain "Usage text" output)))

  (it "prints errors and exits with status 1"
    (let [status (atom nil)
          output (with-out-str
                   (with-redefs [core/exit! (fn [s] (reset! status s))]
                     (#'core/handle-main-result {:error "Bad args" :usage "Usage text"})))]
      (should= 1 @status)
      (should-contain "Bad args" output)
      (should-contain "Usage text" output)))

  (it "dispatches to scan-mutation-sites for scan input"
    (let [received (atom nil)]
      (with-redefs [core/scan-mutation-sites (fn [source-path mutation-warning]
                                               (reset! received {:source-path source-path
                                                                 :mutation-warning mutation-warning}))]
        (#'core/handle-main-result {:source-path "src/foo.cljc"
                                    :scan true
                                    :mutation-warning 75})
        (should= {:source-path "src/foo.cljc"
                  :mutation-warning 75}
                 @received))))

  (it "dispatches to update-manifest! for update-manifest input"
    (let [received (atom nil)]
      (with-redefs [core/update-manifest! (fn [source-path]
                                            (reset! received source-path))]
        (#'core/handle-main-result {:source-path "src/foo.cljc"
                                    :update-manifest true})
        (should= "src/foo.cljc" @received))))

  (it "dispatches to run-mutation-testing for valid input"
    (let [received (atom nil)]
      (with-redefs [core/run-mutation-testing
                    (fn [source-path lines timeout-factor test-command max-workers since-last-run mutate-all mutation-warning]
                      (reset! received {:source-path source-path
                                        :lines lines
                                        :timeout-factor timeout-factor
                                        :test-command test-command
                                        :max-workers max-workers
                                        :since-last-run since-last-run
                                        :mutate-all mutate-all
                                        :mutation-warning mutation-warning}))]
        (#'core/handle-main-result {:source-path "src/foo.cljc"
                                    :lines #{3}
                                    :timeout-factor 7
                                    :test-command "clj -M:all-tests"
                                    :max-workers 2
                                    :since-last-run true
                                    :mutate-all false
                                    :mutation-warning 75})
        (should= {:source-path "src/foo.cljc"
                  :lines #{3}
                  :timeout-factor 7
                  :test-command "clj -M:all-tests"
                  :max-workers 2
                  :since-last-run true
                  :mutate-all false
                  :mutation-warning 75}
                 @received)))))

(describe "-main"
  (it "shuts down agents after successful command handling"
    (let [handled (atom nil)
          shutdowns (atom 0)]
      (with-redefs [core/validate-args (fn [args] {:validated args})
                    core/handle-main-result (fn [validated] (reset! handled validated))
                    core/shutdown-runtime! (fn [] (swap! shutdowns inc))]
        (core/-main "src/foo.cljc" "--scan")
        (should= {:validated ["src/foo.cljc" "--scan"]} @handled)
        (should= 1 @shutdowns))))

  (it "shuts down agents when command handling throws"
    (let [shutdowns (atom 0)]
      (should-throw Exception
                    (with-redefs [core/validate-args (fn [_] {:source-path "src/foo.cljc"})
                                  core/handle-main-result (fn [_] (throw (Exception. "boom")))
                                  core/shutdown-runtime! (fn [] (swap! shutdowns inc))]
                      (core/-main "src/foo.cljc")))
      (should= 1 @shutdowns))))

(describe "line numbers stable across stamp"
  (tags :no-mutate)

  (it "reported survivor lines from full run work with --lines"
    (let [temp-file (java.io.File/createTempFile "mutant" ".cljc")
          temp-path (.getPath temp-file)
          original "(ns test-ns)\n(defn foo [] (+ 1 2))\n"]
      (spit temp-path original)
      (with-redefs [runner/run-specs (fn [& _] :survived)
                    runner/run-specs-timed (fn [cmd]
                                             (should= "clj -M:spec --tag ~no-mutate" cmd)
                                             {:result :survived :elapsed-ms 100})
                    coverage/load-coverage (fn [_] nil)
                    core/run-mutations-parallel
                    (fn [sites source-path content timeout-ms max-workers test-command]
                      (should= nil max-workers)
                      (let [results (doall (map-indexed
                                             (fn [i site]
                                               (let [r (core/mutate-and-test source-path content nil site timeout-ms test-command)]
                                                 (#'core/print-progress i (count sites) r site)
                                                 r))
                                             sites))]
                        results))]
        (let [report (with-out-str
                       (core/run-mutation-testing temp-path))
              plus-match (re-find #"L(\d+)\s+\+ -> -" report)
              reported-line (when plus-match (parse-long (second plus-match)))]
          (should-not-be-nil reported-line)
          (let [lines-report (with-out-str
                               (core/run-mutation-testing temp-path
                                                         #{reported-line}))]
            (should-contain "+ -> -" lines-report))))
      (.delete temp-file))))

(describe "mutate-and-test-in-dir"
  (it "writes mutated content to worker dir, calls run-specs with dir, restores"
    (let [worker-dir (doto (java.io.File. (str "target/test-worker-" (System/nanoTime)))
                       (.mkdirs))
          worker-path (.getPath worker-dir)
          source-rel "src/test_ns.cljc"
          source-file (java.io.File. worker-dir source-rel)
          _ (.mkdirs (.getParentFile source-file))
          original-content "(ns test-ns)\n(defn foo [] (+ 1 2))\n"
          _ (spit (.getPath source-file) original-content)
          forms (core/read-source-forms original-content)
          sites (core/discover-all-mutations forms)
          plus-site (first (filter #(= (:original %) '+) sites))
          received-dir (atom nil)]
      (with-redefs [runner/run-specs (fn [timeout dir cmd]
                                       (should= "clj -M:spec" cmd)
                                       (reset! received-dir dir)
                                       ;; Verify mutated content is on disk
                                       (should-contain "(- 1 2)" (slurp (.getPath source-file)))
                                       :killed)]
        (let [result (core/mutate-and-test-in-dir worker-path source-rel
                                                   original-content plus-site 30000 "clj -M:spec")]
          (should= :killed (:result result))
          (should= worker-path @received-dir)
          ;; Original content should be restored
          (should= original-content (slurp (.getPath source-file)))))
      ;; Cleanup
      (.delete source-file)
      (.delete (.getParentFile source-file))
      (.delete worker-dir))))

(describe "integration: discover mutations in a real source file"
  (it "finds mutation sites in mutations.cljc"
    (let [content (slurp "src/clj_mutate/mutations.cljc")
          forms (core/read-source-forms content)
          sites (core/discover-all-mutations forms)]
      (should (> (count sites) 0))
      (println (format "Found %d mutation sites in mutations.cljc" (count sites))))))

(describe "run-mutations-parallel"
  (it "tests all mutations and returns results sorted by index"
    (let [sites [{:index 0 :original '+ :mutant '- :line 5 :description "+ -> -"}
                 {:index 1 :original '> :mutant '>= :line 7 :description "> -> >="}
                 {:index 2 :original '= :mutant 'not= :line 9 :description "= -> not="}]
          call-count (atom 0)]
      (with-redefs [core/mutate-and-test-in-dir
                    (fn [_ _ _ site _ _]
                      (swap! call-count inc)
                      {:site site :result :killed :timeout? false})
                    workers/new-run-base-dir
                    (fn [root] (str root "/run-test"))
                    workers/create-worker-dirs!
                    (fn [base _ _ n]
                      (should= "target/mutation-workers/run-test" base)
                      (vec (repeat n "target/fake-worker")))
                    workers/cleanup-worker-dirs! (fn [_] nil)]
        (let [results (core/run-mutations-parallel
                        sites "src/foo.cljc" "(ns foo)" 30000 nil "clj -M:spec")]
          (should= 3 (count results))
          (should= 3 @call-count)
          ;; Results sorted by index
          (should= [0 1 2] (mapv #(:index (:site %)) results))
          ;; All killed
          (should (every? #(= :killed (:result %)) results))))))

  (it "works with more mutations than workers"
    (let [sites (vec (for [i (range 10)]
                       {:index i :original '+ :mutant '- :line (+ 5 i)
                        :description (str "mut-" i)}))]
      (with-redefs [core/mutate-and-test-in-dir
                    (fn [_ _ _ site _ _]
                      {:site site :result :killed :timeout? false})
                    workers/new-run-base-dir
                    (fn [root] (str root "/run-test"))
                    workers/create-worker-dirs!
                    (fn [base _ _ n]
                      (should= "target/mutation-workers/run-test" base)
                      (vec (repeat n "target/fake-worker")))
                    workers/cleanup-worker-dirs! (fn [_] nil)]
        (let [results (core/run-mutations-parallel
                        sites "src/foo.cljc" "(ns foo)" 30000 nil "clj -M:spec")]
          (should= 10 (count results))
          (should= (vec (range 10)) (mapv #(:index (:site %)) results))))))

  (it "limits worker directory count when max-workers is provided"
    (let [sites (vec (for [i (range 5)]
                       {:index i :original '+ :mutant '- :line (+ 5 i)
                        :description (str "mut-" i)}))
          created-workers (atom nil)]
      (with-redefs [core/mutate-and-test-in-dir
                    (fn [_ _ _ site _ _]
                      {:site site :result :killed :timeout? false})
                    workers/new-run-base-dir
                    (fn [root] (str root "/run-test"))
                    workers/create-worker-dirs!
                    (fn [_ _ _ n]
                      (reset! created-workers n)
                      (vec (repeat n "target/fake-worker")))
                    workers/cleanup-worker-dirs! (fn [_] nil)]
        (core/run-mutations-parallel sites "src/foo.cljc" "(ns foo)" 30000 2 "clj -M:spec")
        (should= 2 @created-workers)))))

(run-specs)
