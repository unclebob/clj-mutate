# clj-mutate

Mutation testing for Clojure. Discovers mutation sites, applies each one, runs your specs, reports killed/survived.

## Setup

Use either a Babashka `bb.edn` task or normal Clojure `deps.edn` aliases.
Babashka is recommended for day-to-day use because it starts much faster and avoids JVM startup overhead in the `clj-mutate` launcher.
The `clj` launcher remains fully supported and is useful as a compatibility fallback when debugging runtime-specific behavior.
The default commands use [Speclj](https://github.com/slagyr/speclj); another test runner can be used with `--test-command` as long as it returns a conventional process exit status.

For Babashka, add the clj-mutate sources and dependencies along with `spec` and `mutate` tasks:

```clojure
{:paths ["src" "spec" "/path/to/clj-mutate/src"]
 :deps {org.clojure/tools.reader {:mvn/version "1.4.2"}
        rewrite-clj/rewrite-clj {:mvn/version "1.2.55"}
        speclj/speclj {:mvn/version "3.12.2"}}
 :tasks {spec {:doc "Run specs"
               :requires ([speclj.main :as speclj])
               :task (apply speclj/-main "-c" "spec" *command-line-args*)}
         mutate {:doc "Run clj-mutate"
                 :requires ([clj-mutate.core :as core])
                 :task (apply core/-main *command-line-args*)}}}
```

For normal Clojure, add matching `:spec`, `:cov`, and `:mutate` aliases. The `:cov` alias is optional if you intend to use `--no-coverage`:

```clojure
{:paths ["src"]
 :aliases
 {:spec {:main-opts ["-m" "speclj.main" "-c" "spec"]
         :extra-deps {speclj/speclj {:mvn/version "3.12.2"}}
         :extra-paths ["spec"]}

  :cov {:main-opts ["-m" "speclj.cloverage"
                    "-c" "--tag" "~no-mutate" "spec"
                    "--" "-p" "src" "-s" "spec" "--lcov"]
        :extra-deps {cloverage/cloverage {:mvn/version "1.2.4"}
                     speclj/speclj {:mvn/version "3.12.2"}}
        :extra-paths ["spec"]}

  :mutate {:main-opts ["-m" "clj-mutate.core"]
           :extra-deps {clj-mutate/clj-mutate
                        {:local/root "/path/to/clj-mutate"}}}}}
```

The `:spec` and `:cov` aliases above both select `spec`, satisfying the requirement that coverage and mutation workers use the same test population.

## Usage

Both launchers accept the same options:

```bash
clj -M:mutate src/myapp/foo.cljc --scan
bb mutate src/myapp/foo.cljc --scan
```

```bash
# Analyze spec structure and SCRAP scores
clj -M:scrap spec

# Mutate-test a source file.
# If the file already has a footer manifest, this defaults to changed top-level forms only.
clj -M:mutate src/myapp/foo.cljc

# Scan a file for mutation counts without running coverage or specs
clj -M:mutate src/myapp/foo.cljc --scan

# Rewrite the embedded manifest without claiming that mutations passed
clj -M:mutate src/myapp/foo.cljc --update-manifest

# Retest only specific lines (e.g. survivors from a previous run)
clj -M:mutate src/myapp/foo.cljc --lines 45,67,89

# Retest one exact mutant shown in a report
clj -M:mutate src/myapp/foo.cljc --mutation M017

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

# Use matching custom test and coverage profiles
clj -M:mutate src/myapp/foo.cljc \
  --test-command "clj -M:mutation-spec" \
  --coverage-command "clj -M:mutation-cov" \
  --test-roots "spec"

# Explicitly run every selected mutant without LCOV filtering
clj -M:mutate src/myapp/foo.cljc \
  --test-command "clj -M:mutation-spec" \
  --test-roots "spec" \
  --no-coverage

# Show command usage help
clj -M:mutate --help
bb mutate --help
```

The tool automatically:

- Runs the runtime-specific baseline (`clj -M:spec --tag ~no-mutate` or `bb spec --tag ~no-mutate`) to verify all included specs pass unmodified
- Applies each mutation, runs all specs with a timeout (`--timeout-factor`, default 10x baseline)
- Targets exact concrete-syntax nodes, preserving comments and formatting
- Restores the original file after each mutation
- Writes a verified embedded footer manifest only after an unscoped full or differential run kills every selected mutant and leaves no uncovered mutations
- Updates that embedded manifest after successful differential runs as well as full runs
- Defaults to differential mutation when that footer manifest is already present
- Prints a warning when mutation count exceeds `--mutation-warning` (default `100`)
- Excludes specs tagged `:no-mutate` by default so mutation workers do not recursively launch nested mutation runs
- Can reuse existing LCOV data with `--reuse-lcov`

Runs narrowed with `--lines` or `--mutation` never update the verified manifest, even when their selected mutants are killed.

`--scan` is the fast structural mode. It skips coverage, skips test execution, and reports:
- total mutation sites
- changed mutation sites relative to the embedded manifest
- the standard mutation-count warning

`--update-manifest` rewrites the embedded footer manifest for the file's current contents without running coverage, baseline specs, or mutation workers. The result is marked unverified, so it cannot cause a no-change short circuit.

## Recommended Workflow

Run mutation testing one file at a time.

Before running mutation work, run SCRAP on your specs:

```bash
clj -M:scrap spec
clj -M:spec
```

`clj -M:scrap` includes the structural checks that were previously handled by `speclj-structure-check`, and also reports SCRAP scores for the worst examples in each spec file. The alias pulls SCRAP from [`github.com/unclebob/scrap`](https://github.com/unclebob/scrap).

If you have specs that should never run from inside mutation workers, tag them `:no-mutate`. `clj-mutate` excludes those by default with `clj -M:spec --tag ~no-mutate`. You can override that behavior with `--test-command`.

After specs pass, run `--scan` on the files you changed:

```bash
clj -M:mutate src/myapp/foo.cljc --scan
```

If a changed file reports more than `100` mutation sites, consider splitting it before doing full mutation work.

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
- a versioned SHA-256 hash of its normalized original source slice
- the mutation-rule version and effective test-profile fingerprint

Differential mutation runs update the footer manifest on success, so the next differential run compares against the latest successful mutation baseline.
If the source, mutation rules, or effective test profile has not changed, the tool reports `No mutations to test` without loading coverage, running the baseline, or creating workers. Version-1 manifests are treated as stale and upgraded only after a successful run. Source hashes are portable between JVM Clojure and Babashka, but verification remains tied to the effective test profile. Moving between the repository's different JVM and Babashka suites therefore re-verifies unchanged source instead of trusting results from another population.

Every mutant is reported with a file-global identifier such as `M017`, its persistent form/path/rule identity, and an exact `line:column` location. Repeated named top-level forms receive occurrence suffixes such as `defn/foo#2`, keeping persistent identities unique. Use `--mutation M017` (or the persistent identity) for a precise rerun.

## Exit Statuses

| Status | Meaning |
| ---: | --- |
| `0` | All selected mutants were killed, no mutations needed testing, or a reporting command succeeded |
| `1` | Invalid arguments, missing inputs, or coverage/configuration failure |
| `2` | The unmodified baseline tests failed |
| `3` | Survivors or in-scope uncovered mutations remain |
| `4` | Internal mutation-engine failure, such as a no-op or mismatched syntax target |

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

Coverage freshness and provenance are checked automatically:

- On JVM Clojure, missing or stale `target/coverage/lcov.info` is regenerated with `clj -M:cov --lcov`.
- Babashka has no default coverage command. It runs selected mutations without LCOV filtering unless `--coverage-command` is supplied.
- `target/coverage/clj-mutate.edn` records the coverage command, test command, and effective test-profile fingerprint.
- The selected `deps.edn` alias or `bb.edn` task and its effective test-root files are fingerprinted; unrelated aliases are ignored.
- A custom `--test-command` requires a matching `--coverage-command`, or `--no-coverage` to disable LCOV filtering explicitly. If roots cannot be inferred from the selected aliases/tasks, declare the shared population with `--test-roots`; roots must be existing directories relative to the project.
- The resolved test roots are linked into each mutation worker so custom profiles execute the same files that were fingerprinted for provenance.
- During regeneration, existing LCOV is moved aside. It is replaced only when the coverage command creates a fresh, parseable artifact; otherwise the prior file is restored and its provenance is not changed.
- If a mutation site sits on a `recur` argument line or a nested loop-state update expression, LCOV may emit no `DA` entry for that line. In that case `clj-mutate` classifies the site as uncovered even when behavior-level tests exercise the path.

With `--reuse-lcov`:
- `clj-mutate` first verifies that the recorded test profile matches the requested run
- stale matching coverage is allowed and produces one human-readable, timezone-qualified warning
- fresh matching coverage produces one concise reuse message
- if `target/coverage/lcov.info` is missing, the run prints a clear error and exits with status `1`
- unknown or mismatched provenance is rejected instead of being silently trusted

```clojure
:cov {:main-opts ["-m" "speclj.cloverage"
                  "-c" "--tag" "~no-mutate" "spec" "spec-jvm"
                  "--" "-p" "src" "-s" "spec" "-s" "spec-jvm" "--lcov"]
      :extra-deps {cloverage/cloverage {:mvn/version "1.2.4"}
                   speclj/speclj {:mvn/version "3.12.2"}}
      :extra-paths ["spec" "spec-jvm"]}
```

This repository keeps runtime-neutral specs in `spec/`, JVM process-proxy specs in `spec-jvm/`, and Babashka replacements in `spec-bb/`. Its JVM and Babashka commands each run the common suite plus the appropriate runtime-specific suite. This is repository configuration only: `clj-mutate` never substitutes tests in an external project.

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
