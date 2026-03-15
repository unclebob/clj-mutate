# SCRAP

SCRAP is a structural quality analyzer for Speclj specs.

It is aimed at test code in the same way CRAP is aimed at production code. SCRAP does not use mutation results. It measures structural complexity, weak-spec smells, and structural duplication.

Its overall goal is to guide an AI assistant in whether, where, and how to refactor a poorly structured spec file.

The output is a set of recommendations, not directives. An AI assistant should treat SCRAP as decision support, then confirm the recommendation against the actual structure and intent of the spec file before refactoring.

## Usage

Run SCRAP against the whole spec tree:

```bash
clj -M:scrap spec
```

Show the full metric dump for each file, block, and example:

```bash
clj -M:scrap spec --verbose
```

Emit machine-readable JSON instead of the text report:

```bash
clj -M:scrap spec --json
```

Write a sidecar baseline under `target/scrap/`:

```bash
clj -M:scrap spec --write-baseline
```

Compare the current report to an earlier baseline:

```bash
clj -M:scrap spec --compare target/scrap/spec.json
```

Run SCRAP against a specific file or directory:

```bash
clj -M:scrap spec/clj_mutate/core_spec.clj
clj -M:scrap spec/clj_mutate
```

If no path is provided, SCRAP defaults to `spec`.

## What It Checks

SCRAP includes structural validation for common Speclj mistakes:

- `(it)` inside `(it)`
- `(describe)` inside `(describe)` or `(context)`
- `(before)`, `(with-stubs)`, `(around)`, `(with)`, or `(context)` inside `(it)`
- unclosed forms
- parse errors

## What It Measures

Per `it` example:

- line count
- raw line count
- assertion count
- branch count
- setup depth
- `with-redefs` count
- helper-call count
- helper-hidden line count
- whether the example is already table-driven
- temp-resource work
- SCRAP score
- smell labels

Per `describe` or `context` block:

- example count
- average SCRAP
- max SCRAP
- duplication score
- worst example

Per spec file:

- example count
- average SCRAP
- max SCRAP
- branching example count
- low-assertion example count
- zero-assertion example count
- `with-redefs` example count
- helper-hidden example count
- duplication score
- harmful duplication score
- effective duplication score
- setup duplication score
- assertion duplication score
- fixture duplication score
- literal duplication score
- arrange duplication score
- subject repetition score
- coverage-matrix candidate count
- case-matrix repetition count
- setup, assertion, literal, and arrange shape diversity
- average setup, assertion, fixture, literal, arrange, and subject similarity

## Fuzzy Duplication

Duplication is structural and fuzzy.

SCRAP parses forms and normalizes them before comparison:

- symbols become `sym`
- strings become `:string`
- numbers become `:number`
- booleans become `:boolean`
- collection shape is preserved

That means comments and whitespace do not matter, and examples can still match when:

- local variable names differ
- literal values differ
- setup or arrange scaffolding is structurally similar but not textually identical

SCRAP compares normalized feature sets with Jaccard similarity and treats examples as duplicated when similarity is at least `0.5`.

It now splits repetition into separate channels:

- harmful duplication: repeated setup, assertion, fixture, or arrange scaffolding
- case-matrix repetition: repeated low-complexity examples that are likely coverage tables
- subject repetition: repeated focus on the same production API, which is often fine and is not heavily penalized

## Output

By default, SCRAP reports guidance for an AI assistant:

- whether a file should be refactored
- whether the right move is `SPLIT`, `LOCAL`, or `STABLE`
- where the pressure is concentrated
- how to refactor it
- the worst examples in the file

With `--verbose`, SCRAP reports the full metric set:

- file-level summaries
- block-level summaries
- per-example metrics
- a global worst-examples list

With `--json`, SCRAP emits the same report structure as data.

With `--write-baseline`, SCRAP writes a sidecar baseline to `target/scrap/`.

With `--compare`, SCRAP attaches comparison data to each file report:

- verdict: `improved`, `worse`, `mixed`, or `unchanged`
- file-score delta
- average/max SCRAP deltas
- harmful duplication delta
- case-matrix delta
- helper-hidden delta

If a refactor made the file structurally worse, the text report says so explicitly and recommends reverting or simplifying helper extraction.

SCRAP distinguishes between:

- harmful duplication: repeated setup, fixture, or arrange scaffolding that should usually be extracted or split
- coverage-matrix repetition: many small, low-complexity examples with similar structure that are often better converted into table-driven checks

SCRAP also tracks helper-hidden complexity. If an example gets shorter only because setup moved into a spec-local helper, the helper body is still charged back to the example. That prevents helper extraction from looking like an automatic improvement.

That distinction exists so an AI assistant does not misread a block like option parsing or validation matrices as purely bad duplication.

The intended use is to find:

- large examples
- weakly asserted examples
- logic-heavy examples
- mocking-heavy examples
- repeated setup or arrange scaffolding
- spec files or describe blocks that should be split

In practice, SCRAP is meant to answer three refactoring questions for an AI assistant:

- whether a spec file is poorly structured enough to justify refactoring
- where the worst structure is concentrated: file, block, or example
- how the refactor should proceed: split blocks, extract setup, reduce duplication, or simplify oversized examples

The default non-verbose report is optimized around those three questions. `--verbose` is the raw supporting data.

When SCRAP reports `coverage-matrix-candidates`, the intended interpretation for an AI assistant is:

- this repetition may be intentional test coverage
- prefer consolidating it into table-driven specs
- do not treat it as strong evidence that the underlying production logic or spec file structure is fundamentally broken

When SCRAP reports high `effective-duplication-score`, the intended interpretation is different:

- repeated scaffolding is dominating the file or block
- extract setup, extract helpers, or split the block/file

Recommendation lines are ranked by confidence:

- `HIGH`: directly actionable structural problems such as zero-assertion specs, oversized examples, or obvious coverage-matrix cases
- `MEDIUM`: likely cleanup opportunities such as harmful duplication or heavy mocking
- `LOW`: broader design suggestions such as splitting a file by responsibility

SCRAP can also report `STABLE`. That means the file is noisy enough to measure but not problematic enough to justify refactoring right now.

SCRAP also reports a `remediation-mode`:

- `STABLE`: leave the file alone
- `LOCAL`: keep the file intact and clean up individual examples, assertions, or repeated scaffolding
- `SPLIT`: the pressure is spread across enough hotspots that the first move should be splitting the spec file by responsibility before local cleanup

That distinction is important for an AI assistant. A `SPLIT` recommendation means:

- do not spend time polishing isolated examples first
- identify coherent describe/context groups
- split the file into narrower spec namespaces
- then rerun SCRAP on the resulting files for local cleanup guidance

A `LOCAL` recommendation means the opposite:

- keep the file together
- fix assertions, duplication, or oversized examples in place

## Baselines

SCRAP baselines are sidecar analysis artifacts, not source comments.

They live under `target/scrap/` because SCRAP scores are heuristic and tool-version dependent. Embedding them in spec files would create noisy diffs and stale source metadata.

The intended workflow is:

1. Run `clj -M:scrap spec/path --write-baseline`
2. Refactor the spec
3. Run `clj -M:scrap spec/path --compare target/scrap/...`
4. If the verdict is `worse`, inspect the helper-hidden and harmful-duplication deltas before keeping the refactor

In both cases, the recommendation is advisory. The assistant should inspect the file and decide whether the recommendation fits the real testing intent before changing code.
