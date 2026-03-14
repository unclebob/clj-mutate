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
# Mutate-test a source file.
# If the file already has a footer manifest, this defaults to changed top-level forms only.
clj -M:mutate src/myapp/foo.cljc

# Scan a file for mutation counts without running coverage or specs
clj -M:mutate src/myapp/foo.cljc --scan

# Rewrite the embedded manifest without running coverage or mutations
clj -M:mutate src/myapp/foo.cljc --update-manifest

# Retest only specific lines (e.g. survivors from a previous run)
clj -M:mutate src/myapp/foo.cljc --lines 45,67,89

# Force differential mutation even if you want to be explicit
clj -M:mutate src/myapp/foo.cljc --since-last-run

# Override the default differential behavior and mutate all covered sites
clj -M:mutate src/myapp/foo.cljc --mutate-all

# Reuse existing LCOV data without refreshing coverage
clj -M:mutate src/myapp/foo.cljc --reuse-lcov

# Warn when a module exceeds a mutation-count threshold
clj -M:mutate src/myapp/foo.cljc --mutation-warning 75

# Limit parallel worker count
clj -M:mutate src/myapp/foo.cljc --max-workers 4

# Use a custom infinite-loop timeout multiplier (baseline factor)
clj -M:mutate src/myapp/foo.cljc --timeout-factor 15

# Use a custom test command (quote commands containing spaces)
clj -M:mutate src/myapp/foo.cljc --test-command "clj -M:spec --tag ~slow"

# Show command usage help
clj -M:mutate --help
```

The tool automatically:
- Runs a baseline test (`clj -M:spec --tag ~no-mutate`) to verify all included specs pass unmodified
- Applies each mutation, runs all specs with a timeout (`--timeout-factor`, default 10x baseline)
- Restores the original file after each mutation
- Writes an embedded footer manifest with the last test date and top-level form hashes
- Updates that embedded manifest after successful differential runs as well as full runs
- Defaults to differential mutation when that footer manifest is already present
- Prints a warning when mutation count exceeds `--mutation-warning` (default `50`)
- Excludes specs tagged `:no-mutate` by default so mutation workers do not recursively launch nested mutation runs
- Can reuse existing LCOV data with `--reuse-lcov`

`--scan` is the fast structural mode. It skips coverage, skips test execution, and reports:
- total mutation sites
- changed mutation sites relative to the embedded manifest
- the standard mutation-count warning

`--update-manifest` rewrites the embedded footer manifest for the file's current contents without running coverage, baseline specs, or mutation workers.

## Recommended Workflow

Run mutation testing one file at a time.

Before running specs, run [speclj-structure-check](https://github.com/unclebob/speclj-structure-check). A project can also wire this into its `:spec` alias so structure validation runs before Speclj.

```bash
clj -M:spec
```

If you have specs that should never run from inside mutation workers, tag them `:no-mutate`. `clj-mutate` excludes those by default with `clj -M:spec --tag ~no-mutate`. You can override that behavior with `--test-command`.

After specs pass, run `--scan` on the files you changed:

```bash
clj -M:mutate src/myapp/foo.cljc --scan
```

If a changed file reports more than `50` mutation sites, consider splitting it before doing full mutation work.

Then mutate exactly one source file with `--max-workers 3`:

```bash
clj -M:mutate src/myapp/foo.cljc --max-workers 3
```

Workflow rules:
- Mutate only one file at a time.
- Before moving to the next file, cover every uncovered mutation in the current file.
- Before moving to the next file, kill every surviving mutation in the current file.
- `clj-mutate` uses LCOV coverage data and regenerates it when stale or missing.
- In a batch of mutation runs, let the first run generate coverage, then consider `--reuse-lcov` for the remaining files if you accept stale-coverage risk.

Recommended loop for each file:
1. Run `clj -M:mutate path/to/file.clj --max-workers 3`.
2. If any mutations are uncovered, add or fix specs until they are covered.
3. If any mutations survive, change code or specs until they are killed.
4. Rerun the same single-file mutation command.
5. Only start the next file when the current file has no uncovered mutations and no survivors.

For local incremental work, once a file has a footer manifest the default run is differential. You can still be explicit:

```bash
clj -M:mutate src/myapp/foo.cljc --since-last-run
```

Before baseline and worker execution, a mutation run prints:
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

Before a push or major release, consider running `--mutate-all` on the files you changed to verify the full file instead of relying only on differential mutation.

The footer manifest is embedded at the end of the source file and records:
- the last successful mutation test date
- each top-level form's id
- its line span
- a hash of its normalized form

Differential mutation runs update the footer manifest on success, so the next differential run compares against the latest successful mutation baseline.

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

If a `:cov` alias is configured with [Cloverage](https://github.com/cloverage/cloverage) and `--lcov` output, the tool reads `target/coverage/lcov.info` to skip mutations on uncovered lines.

Coverage freshness is checked automatically:
- If `target/coverage/lcov.info` is missing, `clj-mutate` regenerates it with `clj -M:cov --lcov`.
- If LCOV is older than current source/spec inputs, `clj-mutate` regenerates it with `clj -M:cov --lcov`.
- The run prints a diagnostic message when regeneration is triggered.

With `--reuse-lcov`:
- `clj-mutate` uses the existing `target/coverage/lcov.info` as-is
- stale coverage is allowed
- the run prints a warning that covered/uncovered classification may be inaccurate
- the run prints whether the LCOV file exists, its last modified time when present, and whether the target source is newer than the LCOV file
- if `target/coverage/lcov.info` is missing, the run prints a clear error and exits with status `1`

```clojure
:cov {:main-opts ["-m" "speclj.cloverage" "--" "-p" "src" "-s" "spec" "--lcov"]
      :extra-deps {cloverage/cloverage {:mvn/version "1.2.4"}
                   speclj/speclj {:mvn/version "3.10.0"}}
      :extra-paths ["spec"]}
```

## Parallel Worker Isolation

Parallel mutation runs now use a unique worker root per run:

`target/mutation-workers/run-<uuid>/worker-N`

This avoids collisions when two mutation runs overlap or when a prior run exits unexpectedly.

## Claude Code Skill

This repo includes a [Claude Code skill](skills/using-clj-mutate/SKILL.md) for AI-assisted mutation testing. Add it to your project's `.claude/settings.json`:

```json
{
  "skills": ["github.com/unclebob/clj-mutate"]
}
```
