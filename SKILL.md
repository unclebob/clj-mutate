---
name: using-clj-mutate
description: Use when mutation-testing Clojure code, assessing test quality beyond coverage, or investigating surviving mutations to strengthen specs
---

# Using clj-mutate

## Overview

Mutation testing for Clojure. Mutates source code systematically, runs your specs against each mutant, and reports which mutations survived (tests didn't catch) vs were killed (tests caught). Surviving mutations reveal gaps in your test suite that line coverage alone cannot detect.

## Setup

clj-mutate auto-detects whether your project uses `bb.edn` (babashka) or `deps.edn` (JVM Clojure) and adjusts its spec runner and worker setup accordingly.

### deps.edn projects

Add a `:mutate` alias to your project's `deps.edn`:

```clojure
:mutate {:main-opts ["-m" "clj-mutate.core"]
         :extra-deps {clj-mutate/clj-mutate {:local/root "/path/to/clj-mutate"}
                      org.clojure/tools.reader {:mvn/version "1.4.2"}}
         :extra-paths ["spec"]}
```

Requires [Speclj](https://github.com/slagyr/speclj) as test runner with a `:spec` alias that runs all specs.

Optional coverage integration (skips mutations on uncovered lines):

```clojure
:cov {:main-opts ["-m" "speclj.cloverage" "--" "-p" "src" "-s" "spec" "--lcov"]
      :extra-deps {cloverage/cloverage {:mvn/version "1.2.4"}
                   speclj/speclj {:mvn/version "3.12.2"}}
      :extra-paths ["spec"]}
```

### bb.edn projects

Add `mutate` and `spec` tasks to your project's `bb.edn`:

```clojure
{:paths ["src" "spec"]
 :deps {clj-mutate/clj-mutate {:local/root "/path/to/clj-mutate"}
        org.clojure/tools.reader {:mvn/version "1.4.2"}
        speclj/speclj {:mvn/version "3.12.2"}}
 :tasks {spec {:doc "Run all specs"
               :requires ([speclj.main :as speclj])
               :task (speclj/-main "-c")}
         mutate {:doc "Run mutation testing"
                 :requires ([clj-mutate.core :as mutate])
                 :task (apply mutate/-main *command-line-args*)}}}
```

**Important: use speclj 3.12.2+.** clj-mutate detects killed mutants by checking whether `bb spec` exits non-zero. Speclj 3.12.2 correctly propagates exit codes under babashka via `{:babashka/exit N}`. Earlier versions call `System/exit` which bb's task runner intercepts, causing bb to always exit 0 — making all mutants appear to survive.

Requires a `spec` task that runs all specs. Cloverage is not available under babashka; coverage-guided filtering is skipped (all lines are mutation-tested). If an `lcov.info` file is present from an external source, it will be used.

## Usage

```bash
# deps.edn projects
clj -M:mutate src/myapp/foo.cljc
clj -M:mutate src/myapp/foo.cljc --lines 45,67,89

# bb.edn projects
bb mutate src/myapp/foo.cljc
bb mutate src/myapp/foo.cljc --lines 45,67,89
```

The tool automatically:
- Detects project type (`bb.edn` or `deps.edn`) and uses the appropriate spec runner
- Runs a baseline test to verify all specs pass unmodified
- Applies each mutation, runs all specs with timeout (10x baseline)
- Restores original file after each mutation
- Stamps source with `;; mutation-tested: YYYY-MM-DD` on full runs
- For deps.edn projects: runs coverage if `lcov.info` is missing or stale
- For bb.edn projects: uses existing `lcov.info` if present, otherwise tests all lines

## Mutation Rules

| Category | Mutations |
|----------|-----------|
| Arithmetic | `+` <-> `-`, `*` -> `/`, `inc` <-> `dec` |
| Comparison | `>` <-> `>=`, `<` <-> `<=` |
| Equality | `=` <-> `not=` |
| Boolean | `true` <-> `false` |
| Conditional | `if` <-> `if-not`, `when` <-> `when-not` |
| Constant | `0` <-> `1` |

## Interpreting Results

| Result | Meaning | Action |
|--------|---------|--------|
| **Killed** | Test caught the mutation | Tests are strong here |
| **Survived** | Tests passed with mutant code | Write a spec that would fail with the mutation |
| **Timeout** | Mutation caused infinite loop | Treated as killed (mutation had observable impact) |
| **Equivalent** | Mutation can't change behavior | Auto-suppressed; no action needed |

## Equivalent Mutation Suppression

Known-equivalent mutations are auto-suppressed to reduce false survivors:
- `<`/`<=`/`>`/`>=` mutations on `(rand)` comparisons
- `=` mutations in `rand-nth` single-element guards
- Constants inside `rand-nth [...]` pools
- `>` -> `>=` at `subvec` boundaries

## Workflow

1. Write specs using BDD/TDD
2. Run mutation testing:
   - deps.edn: `clj -M:mutate src/myapp/foo.cljc`
   - bb.edn: `bb mutate src/myapp/foo.cljc`
3. Review survivors -- each is a test gap
4. Write specs to kill survivors
5. Retest survivors:
   - deps.edn: `clj -M:mutate src/myapp/foo.cljc --lines 45,67`
   - bb.edn: `bb mutate src/myapp/foo.cljc --lines 45,67`
6. Repeat until kill rate is satisfactory

## Common Mistakes

- **Specs not running**: Ensure your `:spec` alias (deps.edn) or `spec` task (bb.edn) runs all specs under `spec/`
- **Specs fail at baseline**: Fix your specs before mutation testing
- **Chasing equivalent mutations**: Some survivors are mathematically equivalent; suppress them rather than writing impossible tests
- **Missing coverage in bb projects**: Cloverage is JVM-only. Babashka projects test all lines by default, which is slower but thorough
- **All mutants survive in bb projects**: Requires speclj 3.12.2+ which propagates exit codes correctly under babashka. Earlier versions always exit 0 regardless of failures, so clj-mutate can't detect killed mutants.
