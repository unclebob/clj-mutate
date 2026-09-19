# clj-mutate Specification

## Purpose

`clj-mutate` performs mutation testing for a single Clojure source file. It discovers supported mutation sites, runs the project's test command against each mutant, and reports which mutants were killed or survived.

## Inputs

- A source file path.
- Optional CLI arguments:
  - `--scan`
  - `--update-manifest`
  - `--reuse-lcov`
  - `--lines L1,L2,...`
  - `--since-last-run`
  - `--mutate-all`
  - `--mutation-warning N`
  - `--timeout-factor N`
  - `--test-command CMD`
  - `--max-workers N`
  - `--help`

## Preconditions

- The source file must exist.
- The baseline test command must pass before mutations are executed.
- When configured, the project's `:cov` alias should produce `target/coverage/lcov.info`.

## Mutation Discovery

- The tool parses the source file as `.cljc` with `:clj` reader features enabled.
- Mutation sites are discovered across all top-level forms.
- Each mutation site includes:
  - mutation index
  - original token
  - mutant token
  - category
  - line and column when available
  - top-level form index

## Mutation Selection

- `--scan` reports mutation counts only and does not execute mutation testing.
- `--update-manifest` is a human override that records every current site as killed without executing mutation testing.
- `--reuse-lcov` reuses existing LCOV coverage data without refreshing it.
- `--lines` restricts execution to covered mutation sites on the specified source lines.
- `--since-last-run` retries survivors and covered sites in new or rewritten top-level forms.
- `--mutate-all` forces execution of all covered mutation sites, even when a snapshot exists.
- If no explicit selection option is given and a snapshot exists, the tool defaults to differential mutation.
- If no snapshot exists and no explicit selection option is given, the tool executes all covered mutation sites.

## Snapshots

- Successful runs write `.metrics/mutate/<path>.edn`.
- Successful differential runs update that snapshot; they do not leave the prior baseline in place.
- A leftover source footer is read once if no snapshot exists, then stripped.
- The snapshot contains:
  - `:version`
  - `:tested-at` as ISO offset date-time
  - `:module-hash`
  - `:outcomes` mapping mutation ids to `:killed` or `:survived`
  - `:forms`, one entry per top-level form
- Each form entry contains:
  - stable id
  - kind
  - start line
  - end line
  - semantic hash
  - killed, survived, uncovered, and operator (`:sites`) counts

## Differential Behavior

- Differential comparison is based on the `.metrics/mutate` snapshot (or a leftover source footer), not git.
- A module-wide semantic hash is checked first.
- If the module hash is unchanged and there are no survivors, zero mutations are executed and the run reports that no mutations need testing.
- If the module hash is unchanged and survivors remain, only those survivors are retried.
- If the module hash differs, top-level form hashes determine which forms changed. New and rewritten forms are fully retested. Unchanged forms retry survivors only and skip previously killed mutants. If that retry set is empty, nothing is executed and the snapshot is rewritten with the new module hash and prior outcomes kept. Do not record zeros for untested forms.
- Differential run headers report:
  - total mutation sites
  - covered mutation sites
  - uncovered mutation sites
  - changed mutation sites
  - whether a manifest exists
  - whether the module hash changed
  - differential surface area
  - manifest-violating surface area

## Coverage Behavior

- If coverage data is unavailable, all mutation sites are treated as covered.
- If `lcov.info` is missing or stale, the tool attempts to regenerate it with `clj -M:cov --lcov`.
- When `--reuse-lcov` is set, the tool does not regenerate `lcov.info`.
- If `--reuse-lcov` is set and `lcov.info` is missing, the tool prints a clear error and exits with status `1`.
- In a batch of mutation runs, one run may refresh coverage and subsequent runs may reuse the same LCOV file with `--reuse-lcov`.
- Mutations on uncovered lines are skipped.

## Execution Behavior

- `--scan` does not run coverage refresh, baseline specs, or mutation workers.
- `--scan` reports total mutation sites and changed mutation sites relative to the embedded manifest.
- `--update-manifest` does not run coverage refresh, baseline specs, or mutation workers.
- The tool runs a baseline test command first.
- The default test command is `clj -M:spec --tag ~no-mutate`.
- Users may override the test runner command with `--test-command CMD`.
- Mutation timeout is `baseline-elapsed-ms * timeout-factor`.
- Mutations are executed in parallel worker directories.
- Each worker mutates a private copy of the source file.
- The original source is restored after each mutant and after interrupted runs.

## Spec Tagging

- Specs tagged `:no-mutate` are excluded from the default mutation worker test command.
- This is intended for specs that directly invoke mutation runs or would otherwise recursively expand mutation work.
- Projects may choose a different test selection strategy by passing `--test-command`.

## Reporting

- The tool prints:
  - source path
  - previous mutation timestamp when available
  - before baseline and worker execution, total/covered/uncovered/changed mutation counts plus manifest and differential-surface metrics
  - when `--reuse-lcov` is active, an explicit warning plus LCOV existence and freshness diagnostics
  - per-mutant progress
  - summary with killed/survived counts
- When total discovered mutations exceed `--mutation-warning`, the tool prints:
  - `WARNING: Found <N> mutations. Consider splitting this module.`
- Default warning threshold is `100`.

## SCRAP Focus Metrics

For a simple standalone parser aimed at spec-quality analysis, the most practical file-focus metrics are syntactic cohesion measures:

- distinct production vars referenced
- distinct production namespaces referenced
- spec-local helper count
- pairwise symbol overlap between examples
- pairwise keyword overlap between examples
- setup-shape diversity
- `with-redefs` target diversity

These are practical because they can be derived directly from parsed forms without macroexpansion or runtime information.

Less practical for a simple standalone parser are metrics that require semantic interpretation, such as:

- true assertion target dispersion
- helper purpose spread
- behavior-mode spread
- semantic subject detection

## SCRAP Tool

- The repo exposes SCRAP through the `:scrap` alias using the standalone GitHub repo `github.com/unclebob/scrap`.
- Run it with `clj -M:scrap spec` or pass specific spec files or directories.
- SCRAP combines:
  - structural Speclj validation
  - per-example SCRAP scoring
  - per-file rollups
  - a worst-examples report
- The integrated structural checks include:
  - `(it)` inside `(it)`
  - `(describe)` inside `(describe)` or `(context)`
  - `(before)`, `(with-stubs)`, `(around)`, `(with)`, or `(context)` inside `(it)`
  - unclosed forms at end of file

## CLI Constraints

- `--scan` may not be combined with `--update-manifest`, `--lines`, `--since-last-run`, `--mutate-all`, `--timeout-factor`, `--test-command`, or `--max-workers`.
- `--update-manifest` may not be combined with `--scan`, `--lines`, `--since-last-run`, `--mutate-all`, `--timeout-factor`, `--test-command`, or `--max-workers`.
- `--lines` may not be combined with `--since-last-run`.
- `--lines` may not be combined with `--mutate-all`.
- `--since-last-run` may not be combined with `--mutate-all`.
- `--timeout-factor`, `--max-workers`, and `--mutation-warning` must be positive integers.
- `--test-command` must not be blank.

## Postconditions

- On successful mutation runs, `.metrics/mutate/<path>.edn` is updated.
- `--update-manifest` writes a snapshot that records every current site as killed, even though no mutation run occurred.
- On baseline failure, mutation execution does not proceed.
- On interrupted runs, a backup file allows restoration on the next invocation.
