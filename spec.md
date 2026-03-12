# clj-mutate Specification

## Purpose

`clj-mutate` performs mutation testing for a single Clojure source file. It discovers supported mutation sites, runs the project's test command against each mutant, and reports which mutants were killed or survived.

## Inputs

- A source file path.
- Optional CLI arguments:
  - `--scan`
  - `--update-manifest`
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
- `--update-manifest` rewrites the embedded manifest for the current file contents without executing mutation testing.
- `--lines` restricts execution to covered mutation sites on the specified source lines.
- `--since-last-run` restricts execution to covered mutation sites in changed top-level forms relative to the embedded manifest.
- `--mutate-all` forces execution of all covered mutation sites, even when a manifest exists.
- If no explicit selection option is given and an embedded manifest exists, the tool defaults to differential mutation at the top-level-form level.
- If no manifest exists and no explicit selection option is given, the tool executes all covered mutation sites.

## Embedded Manifest

- Successful runs write a footer manifest at the end of the source file.
- The manifest contains:
  - `:version`
  - `:tested-at` as ISO offset date-time
  - `:module-hash`
  - `:forms`, one entry per top-level form
- Each form entry contains:
  - stable id
  - kind
  - start line
  - end line
  - semantic hash

## Differential Behavior

- Differential comparison is based on the embedded manifest, not git.
- A module-wide semantic hash is checked first.
- If the module hash is unchanged, zero mutations are executed and the run reports that no mutations need testing.
- If the module hash differs, top-level form hashes determine which forms changed.

## Coverage Behavior

- If coverage data is unavailable, all mutation sites are treated as covered.
- If `lcov.info` is missing or stale, the tool attempts to regenerate it with `clj -M:cov --lcov`.
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
  - total mutation counts
  - per-mutant progress
  - summary with killed/survived counts
- When total discovered mutations exceed `--mutation-warning`, the tool prints:
  - `WARNING: Found <N> mutations. Consider splitting this module.`
- Default warning threshold is `50`.

## CLI Constraints

- `--scan` may not be combined with `--update-manifest`, `--lines`, `--since-last-run`, `--mutate-all`, `--timeout-factor`, `--test-command`, or `--max-workers`.
- `--update-manifest` may not be combined with `--scan`, `--lines`, `--since-last-run`, `--mutate-all`, `--timeout-factor`, `--test-command`, or `--max-workers`.
- `--lines` may not be combined with `--since-last-run`.
- `--lines` may not be combined with `--mutate-all`.
- `--since-last-run` may not be combined with `--mutate-all`.
- `--timeout-factor`, `--max-workers`, and `--mutation-warning` must be positive integers.
- `--test-command` must not be blank.

## Postconditions

- On successful mutation runs, the source file ends with an updated embedded manifest.
- `--update-manifest` updates the embedded manifest even though no mutation run occurred.
- On baseline failure, mutation execution does not proceed.
- On interrupted runs, a backup file allows restoration on the next invocation.
