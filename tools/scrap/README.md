# SCRAP

SCRAP is a structural quality analyzer for Speclj specs.

It is aimed at test code in the same way CRAP is aimed at production code. SCRAP does not use mutation results. It measures structural complexity, weak-spec smells, and structural duplication.

Its overall goal is to guide an AI assistant in whether, where, and how to refactor a poorly structured spec file.

## Usage

Run SCRAP against the whole spec tree:

```bash
clj -M:scrap spec
```

Show the full metric dump for each file, block, and example:

```bash
clj -M:scrap spec --verbose
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
- assertion count
- branch count
- setup depth
- `with-redefs` count
- helper-call count
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
- `with-redefs` example count
- duplication score
- repeated setup examples
- repeated fixture examples
- repeated literal examples
- repeated arrange examples
- setup, literal, and arrange shape diversity
- average setup, fixture, literal, and arrange similarity

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

## Output

By default, SCRAP reports guidance for an AI assistant:

- whether a file should be refactored
- where the pressure is concentrated
- how to refactor it
- the worst examples in the file

With `--verbose`, SCRAP reports the full metric set:

- file-level summaries
- block-level summaries
- per-example metrics
- a global worst-examples list

SCRAP distinguishes between:

- harmful duplication: repeated setup, fixture, or arrange scaffolding that should usually be extracted or split
- coverage-matrix repetition: many small, low-complexity examples with similar structure that are often better converted into table-driven checks

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
