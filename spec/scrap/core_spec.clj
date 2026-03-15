(ns scrap.core-spec
  (:require [scrap.core :as scrap]
            [speclj.core :refer :all]))

(describe "scan-structure"
  (it "reports nested it forms"
    (let [errors (scrap/scan-structure
                   "(describe \"x\"\n  (it \"outer\"\n    (it \"inner\")))\n")]
      (should= 1 (count errors))
      (should-contain "(it) inside (it)" (first errors))))

  (it "reports unclosed forms"
    (let [errors (scrap/scan-structure
                   "(describe \"x\"\n  (it \"outer\"\n    (should= 1 1))")]
      (should (some #(clojure.string/includes? % "unclosed (describe)") errors)))))

(describe "analyze-source"
  (it "collects examples and summary data"
    (let [report (scrap/analyze-source
                   "(describe \"math\"\n  (it \"adds\"\n    (should= 3 (+ 1 2))))\n"
                   "spec/math_spec.clj")]
      (should= nil (:parse-error report))
      (should= [] (:structure-errors report))
      (should= 1 (count (:examples report)))
      (should= 1 (get-in report [:summary :example-count]))
      (should= 1 (get-in report [:summary :low-assertion-examples]))))

  (it "scores complex examples higher and reports smells"
    (let [report (scrap/analyze-source
                   (str "(describe \"workflow\"\n"
                        "  (defn helper [] 42)\n"
                        "  (before (helper))\n"
                        "  (it \"does a lot\"\n"
                        "    (with-redefs [foo inc] (helper))\n"
                        "    (with-redefs [bar dec] (helper))\n"
                        "    (with-redefs [baz identity] (helper))\n"
                        "    (with-redefs [qux str]\n"
                        "      (let [data {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8 :i 9 :j 10 :k 11}\n"
                        "            result (if true (helper) 0)]\n"
                        "        result))\n"
                        "    (should= 42 (helper))\n"
                        "    (helper)\n"
                        "    (should= 42 (helper))))\n")
                   "spec/workflow_spec.clj")
          example (first (:examples report))]
      (should (< 20 (:scrap example)))
      (should-contain "multiple-phases" (:smells example))
      (should-contain "high-mocking" (:smells example))
      (should-contain "literal-heavy-setup" (:smells example))))

  (it "records parse errors while preserving structure errors"
    (let [report (scrap/analyze-source
                   "(describe \"oops\"\n  (it \"bad\" [)\n"
                   "spec/bad_spec.clj")]
      (should (seq (:structure-errors report)))
      (should (string? (:parse-error report)))
      (should= [] (:examples report)))))

(describe "collect-spec-files"
  (it "collects spec files from a directory tree"
    (let [root (java.nio.file.Files/createTempDirectory "scrap-specs" (make-array java.nio.file.attribute.FileAttribute 0))
          nested (.resolve root "nested")
          target (.resolve nested "example_spec.clj")
          ignored (.resolve nested "notes.txt")]
      (java.nio.file.Files/createDirectories nested (make-array java.nio.file.attribute.FileAttribute 0))
      (spit (.toFile target) "(describe \"x\")")
      (spit (.toFile ignored) "ignore")
      (let [files (scrap/collect-spec-files [(.toString root)])]
        (should= [(.toString target)] files)))))

(describe "render-report"
  (it "includes structure errors and worst examples"
    (let [output (scrap/render-report
                   [{:path "spec/foo_spec.clj"
                     :structure-errors ["ERROR line 2: (it) inside (it) at line 1"]
                     :parse-error nil
                     :examples [{:describe-path ["math"]
                                 :name "adds"
                                 :scrap 9
                                 :line-count 4
                                 :assertions 1
                                 :branches 0
                                 :setup-depth 0
                                 :with-redefs 0
                                 :helper-calls 0
                                 :smells []}]
                     :summary {:avg-scrap 9.0
                               :max-scrap 9
                               :example-count 1
                               :branching-examples 0
                               :low-assertion-examples 1
                               :with-redefs-examples 0}}])]
      (should-contain "SCRAP Report" output)
      (should-contain "structure-errors" output)
      (should-contain "Worst Examples" output)
      (should-contain "math / adds" output))))
