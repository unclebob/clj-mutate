# clj-mutate

Mutation testing for Clojure. Discovers mutation sites, applies each one, runs your specs, reports killed/survived.

## Setup

Add a `:mutate` alias to your project's `deps.edn`:

```clojure
:mutate {:main-opts ["-m" "clj-mutate.core"]
         :extra-deps {clj-mutate/clj-mutate {:local/root "/path/to/clj-mutate"}
                      org.clojure/tools.reader {:mvn/version "1.4.2"}}
         :extra-paths ["spec"]}
```

Requires [Speclj](https://github.com/slagyr/speclj) as your test runner.

## Usage

```bash
# Mutate-test a source file (runs all mutations on covered lines)
clj -M:mutate src/myapp/foo.cljc

# Retest only specific lines (e.g. survivors from a previous run)
clj -M:mutate src/myapp/foo.cljc --lines 45,67,89
```

The tool automatically:
- Finds the matching spec file (`src/myapp/foo.cljc` → `spec/myapp/foo_spec.clj`)
- Runs a baseline test to verify specs pass unmodified
- Applies each mutation, runs specs with a timeout (10x baseline)
- Restores the original file after each mutation
- Stamps the source with `;; mutation-tested: YYYY-MM-DD` on full runs

## Mutation Rules

| Category | Mutations |
|----------|-----------|
| Arithmetic | `+` ↔ `-`, `*` → `/`, `inc` ↔ `dec` |
| Comparison | `>` ↔ `>=`, `<` ↔ `<=` |
| Equality | `=` ↔ `not=` |
| Boolean | `true` ↔ `false` |
| Conditional | `if` ↔ `if-not`, `when` ↔ `when-not` |
| Constant | `0` ↔ `1` |

Known-equivalent mutations (e.g. comparisons on `(rand)`, constants inside `rand-nth` pools) are auto-suppressed.

## Coverage Integration

If a `:cov` alias is configured with [Cloverage](https://github.com/cloverage/cloverage) and `--lcov` output, the tool reads `target/coverage/lcov.info` to skip mutations on uncovered lines. If the coverage data is missing or stale, it runs `clj -M:cov` automatically.

```clojure
:cov {:main-opts ["-m" "speclj.cloverage" "--" "-p" "src" "-s" "spec" "--lcov"]
      :extra-deps {cloverage/cloverage {:mvn/version "1.2.4"}
                   speclj/speclj {:mvn/version "3.10.0"}}
      :extra-paths ["spec"]}
```
