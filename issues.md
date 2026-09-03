# Issues Found While Dogfooding `clj-mutate`

Date observed: 2026-09-03

Resolution status: all listed issues were implemented and regression-tested on 2026-09-03. A same-day follow-up audit added transactional LCOV generation, effective custom-profile fingerprints, collision-free repeated-form identities, profile-safe cross-runtime semantics, and the missing end-to-end/negative cases. The original observations and proposed solutions remain below as a record of the dogfood investigation.

## Scope

The following source files were scanned and mutation-tested from the repository root:

- `src/clj_mutate/cli.cljc`
- `src/clj_mutate/coverage.cljc`
- `src/clj_mutate/mutations.cljc`
- `src/clj_mutate/project.cljc`
- `src/clj_mutate/source.cljc`
- `src/clj_mutate/workflow.cljc`

The JVM runs used the default test command and at most four workers. The first run generated fresh LCOV data; later runs used `--reuse-lcov`. Babashka was also exercised through its advertised `bb mutate` entry point.

## Results

| File | Mutants tested | Killed | Observations |
| --- | ---: | ---: | --- |
| `cli.cljc` | 9 | 4 | All five reported survivors were no-op mutations |
| `coverage.cljc` | 8 | 4 | One confirmed no-op plus three real survivors |
| `mutations.cljc` | 24 | 21 | Three real gaps around alternate suppression branches |
| `workflow.cljc` | 23 | 1 | Nearly all relevant specs were excluded by the worker command |
| `project.cljc` | 2 | 2 | Passed |
| `source.cljc` | 5 | 5 | Passed |

The raw mutation percentages should not be treated as reliable until the no-op, duplicate-mutant, and test-population issues below are fixed.

## 1. Mutation rendering silently creates no-op and duplicate mutants

Severity: high

Status: fixed with exact `rewrite-clj` syntax paths, render verification, deterministic identities, and output deduplication.

`mutate-source-text` can return the original source unchanged. It can also render several logically distinct mutation sites to the same mutated source.

All five `false -> true` survivors reported for `cli.cljc` had this site location:

```clojure
{:line 29, :column 3, :original false, :mutant true}
```

Line 29 contains `{:source-path nil`, so there is no `false` token to replace. Directly comparing the output of `mutate-source-text` with its input confirmed that each mutation was a no-op.

A static rendering check across the six sampled files found:

- 114 discovered mutation sites
- 7 no-op mutations: five in `cli.cljc`, one in `coverage.cljc`, and one in `workflow.cljc`
- 5 duplicate-output groups covering 16 sites; the five CLI no-ops form one of those groups

Likely cause:

- Literal values often have no reader metadata, so mutation discovery falls back to an ancestor's line and column.
- `mutate-source-text` searches that approximate line with `replace-first`.
- It neither verifies that a token was replaced nor verifies that the intended occurrence was replaced.
- Multiple identical literals on one line consequently generate identical mutants.

Impact:

- No-op mutants are always reported as survivors when the baseline passes.
- Duplicate mutants waste test runs and distort mutation scores.
- A replacement may target the wrong occurrence of a token.

Proposed solution:

Use a two-layer fix:

1. Add an immediate correctness guard around mutation rendering. Compare the rendered source with the original, reject unchanged output as an internal mutation error rather than a survivor, and deduplicate identical rendered mutants before running tests.
2. Replace approximate line-based token substitution with concrete-syntax-tree targeting, preferably using `rewrite-clj`. Each mutation site should refer to an exact syntax node so repeated literals can be distinguished while whitespace, comments, and surrounding source formatting remain intact.

Avoid rebuilding and printing entire forms with `pr-str`: although structurally simpler, it would rewrite formatting and discard comments. The immediate guard prevents false results while precise syntax-node targeting is implemented.

Acceptance criteria:

- Every accepted mutation changes the source text.
- Distinct mutation sites either produce distinct output or are deduplicated explicitly.
- Mutation rendering fails clearly rather than running tests when the target token cannot be found at the expected location.
- Add regression coverage for multiline maps, literals lacking metadata, and repeated identical literals on one line.

## 2. The default Babashka mutation command cannot pass its baseline

Severity: high

Status: fixed by splitting common, JVM-only, and Babashka replacement suites and selecting them in the repository's runtime-specific commands.

Reproduction:

```shell
bb mutate src/clj_mutate/project.cljc --reuse-lcov --max-workers 1
```

Observed result:

```text
Baseline: FAIL — specs do not pass without mutations. Aborting.
```

Under Babashka, the default test command is:

```text
bb spec --tag ~no-mutate
```

That command runs five JVM-only specs which proxy `java.lang.Process`; Babashka reports `No matching clause: ["java.lang.Process" #{}]`. The dedicated Babashka replacement suite passes 8 examples with 0 failures, but the default mutation command does not use it and does not exclude the incompatible JVM specs.

Impact:

- The README-recommended Babashka mutation workflow cannot execute mutations in this repository using its defaults.

Proposed solution:

Separate the repository's tests by runtime capability:

- `spec/` contains runtime-neutral tests.
- `spec-jvm/` contains tests that require the full JVM, including `java.lang.Process` proxy tests.
- `spec-bb/` contains Babashka equivalents for behavior that cannot be tested the same way under Babashka.

Configure JVM test and mutation commands to run `spec/` plus `spec-jvm/`. Configure Babashka test and mutation commands to run `spec/` plus `spec-bb/`. In both cases, exclude only tests that genuinely launch recursive mutation runs.

Keep this runtime-specific suite selection in the `clj-mutate` repository's own project configuration. It must not introduce automatic test replacement behavior in external projects.

Acceptance criteria:

- The default Babashka baseline runs only compatible tests and includes the replacement specs.
- `bb mutate` reaches mutation execution on an unchanged, passing checkout.
- Add an end-to-end test for the default Babashka command.
- JVM and Babashka each run the common suite plus their runtime-specific suite.
- External projects continue to run only their own configured tests.

## 3. Embedded manifest hashes are not portable across runtimes

Severity: high

Status: fixed with version-2 manifests using SHA-256 digests of normalized original source slices. Source identity is portable across runtimes; verification provenance remains profile-specific, so a different JVM/Babashka test population safely triggers re-verification without falsely claiming that the module hash changed.

After a successful JVM mutation run wrote a new manifest for `project.cljc`, an immediate second JVM run correctly reported the module unchanged. An immediate Babashka run, without editing the source, instead reported:

```text
Changed mutation sites: 1
Module hash changed: yes
```

Manifest hashes are calculated with `hash` over `pr-str` output. This is sensitive to runtime-specific hashing and reader-expanded generated symbols, so equivalent source can produce different hashes depending on the runtime and reader context.

Impact:

- A manifest created by JVM Clojure cannot reliably drive differential mutation under Babashka, or vice versa.
- Unchanged forms may be retested and incorrectly reported as manifest violations.

Proposed solution:

Replace `hash(pr-str(form))` with a stable, runtime-independent digest:

1. Strip the embedded manifest from the source, as the tool does today.
2. Capture the original source-text slice belonging to each top-level form instead of reconstructing it with `pr-str`.
3. Normalize line endings before hashing.
4. Calculate a SHA-256 digest for each form.
5. Calculate the module digest from the ordered form digests.
6. Record the manifest format and hashing algorithm explicitly, for example:

   ```clojure
   {:version 2
    :hash-algorithm :sha256-source-v1
    :module-hash "..."
    :forms [{:id "defn/foo"
             :hash "..."}]}
   ```

Hashing source slices is deliberately conservative: a formatting or comment change may cause a form to be retested. That is safe and predictable, unlike treating unchanged code differently depending on the runtime.

Implementation notes:

- Never include the embedded manifest itself in the digest.
- Do not use Clojure's `hash` for a persistent fingerprint.
- Do not hash `pr-str` output directly because reader-expanded anonymous functions such as `#(...)` can contain generated symbol names.
- Treat version-1 manifests as stale and upgrade them after the next successful mutation run.
- Test repeated invocation as well as JVM-to-Babashka and Babashka-to-JVM round trips.
- Include regression cases for anonymous functions, maps, sets, reader conditionals, comments, formatting changes, and line-ending differences.

A quicker but weaker alternative is to record the runtime and keep separate JVM and Babashka hashes. That avoids cross-runtime comparisons but duplicates mutation baselines and retains unnecessary runtime coupling.

Until the stable format is implemented, use one runtime consistently for mutation runs and manifest updates. JVM Clojure is currently the practical choice for this repository because the Babashka baseline also needs repair.

Acceptance criteria:

- Hashes use a documented, versioned, runtime-independent representation and digest algorithm.
- The same source produces identical module and form hashes under JVM Clojure and Babashka.
- Add a cross-runtime round-trip test proving that module/form hashes remain unchanged. Report zero changes only when verification provenance also matches; differing JVM and Babashka test populations must re-verify the unchanged source.
- Version-1 manifests migrate safely without being mistaken for current successful baselines.

## 4. LCOV coverage and mutation workers use different test populations

Severity: high

Status: fixed with matching mutation-safe coverage/test profiles, explicit custom coverage policy, effective test-root validation, and transactional LCOV provenance. Existing LCOV is moved aside during regeneration and restored on failure, so a successful command that produces no new artifact cannot relabel old coverage.

The coverage command runs the coverage suite without the mutation worker's `~no-mutate` exclusion. Mutation workers use `clj -M:spec --tag ~no-mutate`.

For `workflow.cljc`, LCOV classified all 28 mutation sites as covered, but almost all workflow specs are tagged `:no-mutate` and therefore did not run against the mutants. Only 1 of 23 changed mutants was killed.

Impact:

- “Covered mutation sites” does not mean the mutation worker will execute the tests responsible for that coverage.
- The report presents test exclusions as surviving behavior, producing a severely misleading score.

This was exposed by dogfooding `clj-mutate`, but the underlying problem is general. An external project is also affected whenever it uses `:no-mutate` tags, supplies a custom `--test-command`, or otherwise selects different tests for coverage and mutation execution.

Proposed solution:

Make coverage generation and mutation execution use the same explicit test profile. The governing rule should be:

> A line is covered only if it was covered by tests that will actually run against its mutant.

For this repository:

1. Apply `:no-mutate` only to specs that genuinely launch nested mutation runs. Workflow unit specs that replace the runner or execution layer can safely run against mutants and should not be excluded as a group.
2. Define one mutation-safe test profile that includes ordinary unit specs, excludes only recursive specs, and selects the Babashka replacements when running under Babashka.
3. Use that same profile for both LCOV generation and mutation workers.

For external projects, support an explicit coverage command alongside the test command, for example:

```shell
clj -M:mutate src/foo.cljc \
  --test-command "clj -M:mutation-spec" \
  --coverage-command "clj -M:mutation-cov" \
  --test-roots "spec"
```

The two project aliases must select the same effective tests. The tool cannot reliably infer an equivalent coverage invocation from an arbitrary test command, so explicit project configuration is preferable to command rewriting.

The tool infers effective roots from the selected `deps.edn` alias or `bb.edn` task and rejects mismatched test/coverage roots. Commands whose roots cannot be inferred must use `--test-roots` to declare the shared population explicitly. Those roots and the selected alias/task configuration are included in provenance, and the same roots are linked into each mutation worker. Declared roots must be project-relative so they cannot escape the worker sandbox.

Record coverage provenance beside `lcov.info`, including a fingerprint of the coverage command or named test profile. Before using LCOV, compare that provenance with the requested mutation test profile. If they differ, regenerate coverage or reject `--reuse-lcov` with a clear explanation.

If a project's coverage tooling cannot run the same test population, the tool should offer an explicit fallback that disables LCOV-based filtering rather than silently presenting mismatched coverage as authoritative.

Acceptance criteria:

- Coverage classification uses the same effective test population as mutation execution, or the discrepancy is detected and reported.
- Workflow behavior has a non-recursive test command that can safely execute inside mutation workers.
- A site covered only by an excluded test must not be reported as normally covered.
- A valid LCOV report that omits the mutated source classifies all of that source's sites as uncovered rather than disabling filtering.
- `--reuse-lcov` detects coverage generated by a different command or test profile.
- Add an end-to-end test using different coverage and worker test populations and verify that the mismatch cannot pass silently.

## 5. Survivors and baseline failures return exit status 0

Severity: high for CI usage

Status: fixed with structured workflow outcomes and the documented `0`–`4` process exit policy.

Every mutation run containing survivors exited with status 0. The Babashka run whose baseline failed also exited with status 0.

The CLI currently exits nonzero for argument and missing-LCOV errors, but the workflow does not propagate a failing result for baseline failures, surviving mutants, or uncovered mutations.

Impact:

- CI and shell scripts cannot use the process status to enforce mutation quality.
- A failed mutation-testing job appears successful unless its text output is parsed.

Proposed solution:

Have mutation workflows return structured result data and let only the outermost CLI translate that result into a process exit status. Library-level functions must not terminate the runtime.

Use the following exit-code policy:

| Exit code | Meaning |
| ---: | --- |
| `0` | Successful run, all selected mutants killed, or no mutations needed testing |
| `1` | Invalid arguments, missing files, or configuration/setup error |
| `2` | The unmodified baseline tests failed |
| `3` | Survivors or in-scope uncovered mutations remain |
| `4` | Internal mutation-engine failure, such as a no-op mutation |

Additional rules:

- `--help`, `--scan`, and a successful `--update-manifest` return `0`.
- A targeted `--lines` run judges only its selected mutation scope.
- Mutation timeouts retain the current `:killed` classification.
- Workflow functions return values such as `{:status :survivors ...}` or `{:status :baseline-failed ...}`.
- Only `-main` calls `System/exit`, and only after reporting the structured result.
- Add subprocess-level tests that assert real shell exit codes rather than only replacing `exit!` in unit tests.

Acceptance criteria:

- Define and document exit semantics for baseline failure, survivors, uncovered mutations, timeouts, and successful runs.
- At minimum, baseline failure and surviving mutants return a nonzero status suitable for CI.
- Add command-level tests that assert process exit codes.

## 6. Survivor identifiers are ambiguous

Severity: medium

Status: fixed with global `Mnnn` labels, deterministic form/path/rule identities, exact locations, and `--mutation` selection. Repeated named top-level forms receive `#2`, `#3`, and later occurrence suffixes so persistent selectors remain file-unique.

Mutation indexes restart for every top-level form, but survivor summaries print only the local mutation index, line, and description. The `mutations.cljc` summary consequently contained several different survivors labeled `#2`.

Multiple sites may also share the same approximate line and description. The five CLI no-ops were all printed as:

```text
L29 false -> true
```

Impact:

- A user cannot uniquely identify or reproduce a survivor from the summary.
- Parallel progress order and summary identifiers appear inconsistent.

Proposed solution:

Give every mutation two identifiers:

- A readable global run number such as `M017`, unique within the source file.
- A deterministic identity composed from the top-level form ID, the semantic node path within that form, and the mutation rule ID—for example, `defn/foo/[3,1,2]/boolean-false-to-true`.

The semantic node path should ignore whitespace and comment nodes. This keeps the identity stable across formatting-only edits and fits the concrete-syntax-tree approach proposed for issue 1.

Report mutations consistently, for example:

```text
M017  defn/foo  42:19  SURVIVED  false -> true
```

Assign run numbers globally after discovering all mutations in the file, sort summaries by source position, and display line and column in both progress and summary output. Use the same identifier everywhere, including targeted reruns. Retain `--lines` for broad selection and add a precise selector such as `--mutation M017` or a persistent mutation identity.

Acceptance criteria:

- Give every file-level mutation site a stable, unique identifier.
- Include enough location data—such as form ID, line, column, and occurrence—to distinguish repeated tokens.
- Use the same identifier in progress output, summaries, and targeted reruns.
- Identifier generation is deterministic under JVM Clojure and Babashka.

## 7. Unchanged differential runs still execute the baseline and report 0%

Severity: medium

Status: fixed with trusted manifest provenance and a planning short circuit before coverage, baseline, or worker setup. Provenance fingerprints the selected alias/task plus every source-like file under its inferred or explicitly declared test roots, while ignoring unrelated aliases.

After `project.cljc` received a current manifest, rerunning it produced:

```text
Module hash unchanged; no mutations to test.

Baseline: PASS

=== Summary ===
0/0 mutants killed (0.0%)
```

The baseline still took about 1.5 seconds even though selection had already found no mutations.

Impact:

- Differential no-op runs do unnecessary work.
- `0/0 mutants killed (0.0%)` looks like failure rather than “nothing changed.”

Proposed solution:

Split mutation testing into two phases:

1. Planning reads the source and manifest, validates their provenance, and selects mutations.
2. Execution generates or loads coverage, runs the baseline, and launches mutation workers only when the plan contains mutations.

When no mutations are selected and the manifest represents a trusted successful run, return structured data such as:

```clojure
{:status :no-changes
 :mutations 0}
```

Print an explicit result instead of a percentage, for example:

```text
No changes since the successful mutation run at 2026-09-03T10:58:35-05:00.
No mutations to test.
```

This result returns exit code `0`, performs no coverage refresh, runs no baseline, launches no workers, and does not rewrite the manifest.

The no-change decision must validate more than the source hash. A changed test profile, mutation-rule set, or relevant test suite can invalidate the previous result. Record sufficient provenance—such as the mutation-rule version and test-profile fingerprint—in the manifest so an old result is reused only when its inputs still match. Fingerprint only effective mutation inputs: hashing all of `deps.edn`, for example, would incorrectly invalidate results when an unrelated development tool such as deintroverter changes.

Acceptance criteria:

- Short-circuit before baseline execution when differential selection produces no sites.
- Report an explicit successful no-change outcome without a percentage.
- A valid no-change run does not load or refresh coverage, run baseline tests, create workers, or rewrite the manifest.
- Changes to recorded mutation or test provenance invalidate the no-change result.
- Tests verify that coverage, baseline, and worker functions are not called during a valid no-change run.

## 8. LCOV reuse diagnostics are redundant and hard to read

Severity: low

Status: fixed by returning structured coverage states and rendering one human-readable status message in the reporting layer.

When stale LCOV is reused, output can print both a specific stale-reuse warning and a second generic reuse warning. The `LCOV last modified` value is printed as raw epoch milliseconds, for example `1788451018288`.

Proposed solution:

Make the coverage layer return structured information without printing. For example:

```clojure
{:lines #{1 3 5}
 :status :stale-reused
 :last-modified 1788451018288
 :source-newer? true}
```

Use explicit statuses such as `:fresh`, `:fresh-reused`, `:stale-reused`, `:regenerated`, `:refresh-failed`, and `:missing`. A single reporting layer translates that status into exactly one message, for example:

```text
Reusing stale LCOV generated at 2026-09-03 10:56:58 CDT;
source or test files have changed since it was generated.
```

Keep epoch milliseconds internally but convert them to a human-readable, timezone-qualified value at the reporting boundary. Do not issue a stale warning when freshness is known, and keep missing-LCOV errors separate from ordinary status reporting.

Acceptance criteria:

- Print one consolidated reuse warning.
- Format modification times as human-readable timestamps, ideally including the timezone.
- Coverage-loading functions do not print status messages.
- Each coverage status produces exactly one clear report message.
- Fresh reused coverage does not produce a stale warning.

## Additional test gaps in mutation suppression

Severity: medium, with potential production impact

Status: fixed with branch-aware `if-not` matching, symmetric `>`/`>=` trim suppression, and explicit positive and negative specs for every supported `if`/`if-not` and `>`/`>=` arrangement.

The three genuine survivors in `mutations.cljc` correspond to alternate branches in equivalent-mutant suppression:

- `if-not` handling in the `rand-nth` guard logic
- `>=` handling in the `subvec` boundary logic
- `if-not` handling in the `subvec` boundary logic

The implementation claims to support these forms, but the specs primarily exercise `if` and `>`. Add focused examples for the alternate branches so future changes cannot silently break their suppression behavior.

This was discovered by mutation-testing `clj-mutate` against its own source, but it is not limited to the dogfood case. The suppression matcher is production code used while analyzing every external project. Missing `>=` coverage creates an internal regression risk, while incorrect `if-not` matching may already suppress legitimate mutations in external source.

Proposed solution:

1. Define the valid `if` and `if-not` guard shapes explicitly. For example, the `if-not` equivalent of an `if` guard must reverse its then and else branches.
2. Make the suppression matcher validate the correct branch arrangement instead of accepting `if-not` based only on the head symbol.
3. Add explicit, clearly named positive and negative examples for every supported `if`/`if-not` and `>`/`>=` combination. Keep each case independently visible rather than hiding failures inside a loop or table.
4. Assert through public mutation-discovery behavior that equivalent sites are suppressed and similar but nonequivalent sites remain available for mutation.
5. Rerun mutation testing against `mutations.cljc` and require the three current survivors to be killed.

The code and tests for this fix live in the `clj-mutate` repository, but the corrected matcher applies automatically to every external project. External projects require no replacement tests or configuration changes.

Acceptance criteria:

- Valid `if` and branch-reversed `if-not` guard forms are specified and tested.
- Invalid or nonequivalent `if-not` arrangements are not suppressed.
- Both `>` and `>=` subvec-boundary forms have positive and negative coverage.
- JVM Clojure and Babashka discover the same suppression results.
- Mutation testing kills the three survivors that exposed the missing branches.
