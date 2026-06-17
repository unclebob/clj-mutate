---
name: using-clj-mutate
description: Use when mutation-testing Clojure code, assessing test quality beyond coverage, or investigating surviving mutations to strengthen specs
---

# Using clj-mutate

## Overview

Mutation testing for Clojure. Mutates source code systematically, runs your specs against each mutant, and reports which mutations survived (tests didn't catch) vs were killed (tests caught). Surviving mutations reveal gaps in your test suite that line coverage alone cannot detect.

## Setup

clj-mutate auto-detects whether your project uses `bb.edn` (Babashka) or `deps.edn` (JVM Clojure) and picks the default spec runner accordingly.

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

Use speclj 3.12.2+ under Babashka so failed specs propagate non-zero exit codes correctly.

Cloverage is not available under Babashka; coverage-guided filtering is skipped unless `target/coverage/lcov.info` already exists.

## Usage

```bash
# deps.edn projects
# If the file has a footer manifest, this defaults to changed top-level forms only.
clj -M:mutate src/myapp/foo.cljc

# bb.edn projects
bb mutate src/myapp/foo.cljc

# Scan mutation counts without running coverage or specs
clj -M:mutate src/myapp/foo.cljc --scan

# Rewrite the embedded manifest without running coverage or mutations
clj -M:mutate src/myapp/foo.cljc --update-manifest

# Retest only specific lines (e.g. survivors from previous run)
clj -M:mutate src/myapp/foo.cljc --lines 45,67,89

# Retest only top-level forms changed since the last successful mutation run
clj -M:mutate src/myapp/foo.cljc --since-last-run

# Override differential mode and run all covered mutations
clj -M:mutate src/myapp/foo.cljc --mutate-all

# Reuse existing LCOV data without refreshing coverage
clj -M:mutate src/myapp/foo.cljc --reuse-lcov
```

The tool automatically:
- Detects project type (`bb.edn` or `deps.edn`) and uses the appropriate default spec runner
- Runs a baseline test to verify all included specs pass unmodified
- Applies each mutation, runs all specs with timeout (10x baseline)
- Restores original file after each mutation
- Writes an embedded footer manifest with `:tested-at` and top-level form hashes on successful runs
- Defaults to differential mutation when that footer manifest already exists
- For deps.edn projects: runs coverage if `lcov.info` is missing or stale
- For bb.edn projects: uses existing `lcov.info` if present, otherwise tests all lines
- Can reuse existing LCOV data with `--reuse-lcov`
- Excludes specs tagged `:no-mutate` by default to avoid nested mutation runs inside mutation workers

Use `--scan` when you want a fast module-size signal without paying for coverage or test execution. It reports total mutation sites, changed mutation sites, and the usual warning threshold output.

Use `--update-manifest` when you want to accept the current file contents as the new differential baseline without paying for coverage or mutation execution.

Use `--reuse-lcov` when coverage has already been generated and you want to skip an expensive LCOV refresh. The run will warn that covered/uncovered classification may be stale. If `target/coverage/lcov.info` is missing, the run prints a clear error and exits.

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
2. Run mutation testing (`clj -M:mutate ...` or `bb mutate ...`)
3. Review survivors -- each is a test gap
4. Write specs to kill survivors
5. Retest survivors with `--lines`
6. Repeat until kill rate is satisfactory

For a batch of files, the first mutation run can generate coverage and later runs can use `--reuse-lcov` to avoid repeating LCOV refresh, if you accept the stale-coverage tradeoff.

For incremental work on an already-mutated file, the default run is already differential. You can still be explicit:

```bash
clj -M:mutate src/myapp/foo.cljc --since-last-run
```

This compares current top-level forms with the footer manifest from the last successful mutation run and tests only forms whose hashes changed.

Before baseline and worker execution, differential runs also print:
- total mutation sites
- covered mutation sites
- uncovered mutation sites
- changed mutation sites
- whether a manifest exists
- whether the module hash changed
- differential surface area
- manifest-violating surface area

To force a full rerun on a file with a manifest:

```bash
clj -M:mutate src/myapp/foo.cljc --mutate-all
```

## Common Mistakes

- **Specs not running**: Ensure your `:spec` alias (deps.edn) or `spec` task (bb.edn) runs all specs under `spec/`
- **All mutants survive in bb projects**: Requires speclj 3.12.2+ for correct exit-code propagation under Babashka
- **Missing coverage in bb projects**: Cloverage is JVM-only; Babashka projects test all lines by default
- **Specs fail at baseline**: Fix your specs before mutation testing
- **Chasing equivalent mutations**: Some survivors are mathematically equivalent; suppress them rather than writing impossible tests
- **Recursive mutation runs**: Tag specs that invoke `run-mutation-testing` as `:no-mutate`, or override the worker command with `--test-command`
