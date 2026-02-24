---
name: using-clj-mutate
description: Use when mutation-testing Clojure code, assessing test quality beyond coverage, or investigating surviving mutations to strengthen specs
---

# Using clj-mutate

## Overview

Mutation testing for Clojure. Mutates source code systematically, runs your specs against each mutant, and reports which mutations survived (tests didn't catch) vs were killed (tests caught). Surviving mutations reveal gaps in your test suite that line coverage alone cannot detect.

## Setup

Add a `:mutate` alias to your project's `deps.edn`:

```clojure
:mutate {:main-opts ["-m" "clj-mutate.core"]
         :extra-deps {clj-mutate/clj-mutate {:local/root "/path/to/clj-mutate"}
                      org.clojure/tools.reader {:mvn/version "1.4.2"}}
         :extra-paths ["spec"]}
```

Requires [Speclj](https://github.com/slagyr/speclj) as test runner. Source files must have matching spec files (`src/app/foo.cljc` -> `spec/app/foo_spec.clj`).

Optional coverage integration (skips mutations on uncovered lines):

```clojure
:cov {:main-opts ["-m" "speclj.cloverage" "--" "-p" "src" "-s" "spec" "--lcov"]
      :extra-deps {cloverage/cloverage {:mvn/version "1.2.4"}
                   speclj/speclj {:mvn/version "3.10.0"}}
      :extra-paths ["spec"]}
```

## Usage

```bash
# Mutation-test a source file (all covered lines)
clj -M:mutate src/myapp/foo.cljc

# Retest only specific lines (e.g. survivors from previous run)
clj -M:mutate src/myapp/foo.cljc --lines 45,67,89
```

The tool automatically:
- Finds the matching spec file
- Runs a baseline test to verify specs pass unmodified
- Applies each mutation, runs specs with timeout (10x baseline)
- Restores original file after each mutation
- Stamps source with `;; mutation-tested: YYYY-MM-DD` on full runs
- Runs coverage if `lcov.info` is missing or stale

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
2. Run `clj -M:mutate src/myapp/foo.cljc`
3. Review survivors -- each is a test gap
4. Write specs to kill survivors
5. Retest survivors: `clj -M:mutate src/myapp/foo.cljc --lines 45,67`
6. Repeat until kill rate is satisfactory

## Common Mistakes

- **No matching spec file**: Tool expects `src/` -> `spec/` path mapping with `_spec.clj` suffix
- **Specs fail at baseline**: Fix your specs before mutation testing
- **Chasing equivalent mutations**: Some survivors are mathematically equivalent; suppress them rather than writing impossible tests
