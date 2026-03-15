# SCRAP

SCRAP is a proposed structural quality metric for Speclj specs.

The intent is similar to CRAP, but targeted at test code rather than production code. It does not use mutation data. It scores examples based on structural complexity and spec-design smells.

In this repo, SCRAP is implemented as a standalone tool under `tools/scrap` and run with:

```bash
clj -M:scrap spec
```

The tool also incorporates the structural checks that were previously handled by `speclj-structure-check`.

## Formula

Per `it`:

```text
SCRAP = complexity^2 + smell_penalties
```

## Complexity

```text
complexity = 1
  + branch_count
  + setup_depth
  + helper_indirection
```

Definitions:

- `branch_count`
  Count `if`, `if-not`, `when`, `when-not`, `cond`, `case`, `and`, `or`, `try`, and looping forms.
- `setup_depth`
  Count nested `let`, `binding`, `with-redefs`, `before`, and `around`.
- `helper_indirection`
  Count calls to local helper functions or macros defined in the same spec namespace.

## Smell Penalties

- `+10` no assertions
- `+6` exactly one assertion in an example longer than 10 lines
- `+5` more than one behavioral phase in one `it`
- `+4` more than 3 stubs or redefs
- `+4` line count greater than 20
- `+3` temp file, temp dir, process, or thread work
- `+3` repeated literal-heavy setup
- `+2` multiple unrelated assertion clusters
- `+2` large inline test data blobs

## Measurements

Per `it`, measure:

- line count
- assertion count
- branch count
- nested binding depth
- `with-redefs` count
- helper call count
- temp-resource count
- literal-data size
- number of top-level action/assertion phases

## Heuristics

- `no assertions`
  No `should`, `should=`, `should-not`, `should-contain`, or similar assertion macros.
- `multiple phases`
  More than one act/assert cycle, or several unrelated assertions separated by setup or action.
- `literal-heavy setup`
  Large maps, vectors, strings, or other inline data over a chosen threshold.
- `helper indirection`
  Calls to local helper functions in the same spec namespace, excluding trivial builders if desired.

## Output

Suggested report shape:

```text
=== SCRAP Report ===

spec/foo/bar_spec.clj

  describe: parser

    it: rejects malformed tokens
      SCRAP: 29
      lines: 24
      assertions: 1
      branches: 3
      setup-depth: 4
      redefs: 5
      helper-calls: 2
      smells: low-assertion-density, high-mocking, large-example

    it: parses a simple token
      SCRAP: 3
      lines: 5
      assertions: 2
      branches: 0
      setup-depth: 0
      redefs: 0
      helper-calls: 0
      smells: none

Worst Examples:
  1. parser/rejects malformed tokens      29
  2. workflow/cleans temp directories     24
  3. runner/handles timeout               21
```

## File Rollups

For each spec file:

- average SCRAP
- max SCRAP
- percent of examples with branching
- percent of examples with 0 or 1 assertions
- percent using `with-redefs`
- top repeated setup forms

## Thresholds

- `0-5`: focused
- `6-12`: normal
- `13-20`: questionable
- `21+`: likely poor spec design

## Implementation Rules

Walk Speclj forms and detect:

- `describe`
- `context`
- `it`
- `before`
- `around`
- assertion macros
- setup and mocking forms

The integrated structure checks currently report:

- `(it)` inside `(it)`
- `(describe)` inside `(describe)` or `(context)`
- `(before)`, `(with-stubs)`, `(around)`, `(with)`, or `(context)` inside `(it)`
- unclosed forms at end of file

Then score each `it` based on:

- its own body
- enclosing setup burden

The first useful version should simply flag:

- long examples
- logic-heavy examples
- low-assertion examples
- mocking-heavy examples

That is enough to surface many weak or overly complex specs without any mutation integration.
