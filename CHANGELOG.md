# Changelog — backtracking, longest choice and error-position properties

## Scope

Property-based tests for the parser-combinator backtracking / error-merging surface of
jparsec: `or` (ordered choice), `longest`/`shortest` (length-based choice), `atomic`
(all-or-nothing + logical-step collapse), `peek`/`not` (lookahead), `optional`, `many`
and `otherwise` (empty/zero-width guard). Every property compares the full replayable
evidence of a run — success, physical source position and diagnostic payload (expected
set / unexpected subject / failure message) — not just the returned value.

No production source was changed. All additions are test code.

## Files added (all under `jparsec/src/test`)

- `org/jparsec/properties/G.java` — a tiny closed grammar description (literals, empty,
  `fail`, `expect`, `seq`, `or`, `longest`/`shortest`, `atomic`, `peek`, `not`,
  `optional`, `many`, `otherwise`) with a stable `show()` rendering used in failure
  messages so counter-examples replay directly.
- `org/jparsec/properties/Outcome.java` — the compared evidence record
  (`success`/`expecting`/`unexpected`/`failure`, physical position, expected set).
- `org/jparsec/properties/ReferenceMachine.java` — an independent reference interpreter
  used as the oracle. It tracks both the logical cursor/step and the physical error index,
  the single farthest-error record and its precedence/merge rules, and the frozen-error
  semantics of `withErrorSuppressed` used by `ifelse`/`not`.
- `org/jparsec/properties/Gen.java` — deterministic, seeded (LCG) generator of small
  grammars and inputs, biased toward shared-prefix choices, nullable branches, nested
  backtracking and multiple failure points.
- `org/jparsec/properties/JParsecMachine.java` — compiles a `G` to the real jparsec
  combinators and captures the same `Outcome` shape, at character level and at token
  level (one space-delimited word per token, indexes preserved).
- `org/jparsec/properties/BacktrackingPropertyTest.java` — the property + boundary tests.
- `org/jparsec/properties/SharedPrefixBacktrackingRegressionTest.java` — the canonical
  "most dangerous counter-example" regression (see below).
- `org/jparsec/MutationParsers.java` — test-only, single-boundary mutant parsers.
- `org/jparsec/properties/MutationDetectionTest.java` — proves each mutant is detected.

## Properties and how they are checked

- **Reference agreement (character level), 3000 seeded cases.** Each generated grammar is
  run by the reference interpreter and by the real combinators; the full `Outcome` must be
  equal.
- **Reference agreement (token level), 3000 seeded cases.** Same, through `Parser.from`,
  with the oracle run in token-level coordinates.
- **Token/character position alignment, 800 seeded cases.** Away from combinators that
  compare a physical length against a logical cursor, a one-token-per-symbol lexer must
  report exactly the character-level position (token slot `i` mapped to source index `2i`
  in the spaced source) and the same expected set.
- **Branch-permutation invariance.** Swapping branches that cannot change the result
  (duplicate branches, or distinct branches that fail at the same position) leaves both
  the choice and the merged error record identical — checked against the reference and the
  real parser, in both branch orders, over fixed rows and generated cases.
- **Boundary truth table.** Hand-fixed rows for first-vs-longest-vs-shortest selection,
  nullable first branch, shared-prefix deep failures, atomic rollback, lookahead,
  negative-lookahead diagnostics, precedence of `expect` over missing terminals and the
  trailing-input EOF error.

## Implementation choices

- **Self-contained generator.** The project is JUnit 4 with no property-testing library,
  so generation is a small seeded LCG rather than a new dependency. Seeds are printed in
  every failure message, making counter-examples deterministic and replayable.
- **Two-coordinate oracle.** The production engine keeps a logical token cursor `at`, a
  logical `step`, and a separate physical `errorIndex`. These coincide at character level
  but not at token level. The oracle models both, which is what makes the token-level
  properties meaningful rather than assuming identity.
- **Diagnostics compared as sets.** Production de-duplicates expected names with an
  ordered set when rendering; the tests compare `LinkedHashSet` content so order cannot
  cause spurious failures while loss of any expected token is still detected.

## Coverage gap previously present

Existing tests (`ParserErrorHandlingTest`, `ParsersTest`) pin individual behaviors
(`or` merging, `longer`/`shorter` error survival, a few token-level messages), but there
was no *systematic* check that:

1. backtracking over a shared prefix, nested through `atomic`/`peek`/`or`, stays
   internally consistent across many grammars;
2. the chosen alternative, the farthest error position and the merged expected set all
   agree with a single semantic model at once;
3. token-level and character-level positions correspond for the same grammar;
4. the result is independent of the order of branches that are allowed to be reordered.

The new properties cover those combinations generatively rather than by enumerating
fixtures, and they inspect diagnostics instead of only the return value.

## Deliberate mutations and the regressions that catch them

The test-only `MutationParsers` and `MutationDetectionTest` implement three mutants, each
deleting one mechanism. The same behavior was additionally confirmed by mutating the
real production classes in a scratch run and observing the new suites fail (then
reverting):

- **Position rollback removed** between `or` alternatives
  (`MutationParsers.noRollbackOr`, and a scratch edit to the varargs `or` in
  `Parsers.java`): a branch that consumed the shared prefix poisons the start of the
  next branch. Caught by `sharedPrefixStillAllowsLaterBranchToMatch`, the boundary truth
  table and the generative `charLevelAgreesWithReference` (the scratch product edit
  failed 5 new tests).
- **Error merging removed** (`MutationParsers.noMergeOr`, and a scratch edit to
  `ParseContext.raise` dropping same-position mergeable subjects): same-position expected
  tokens are lost. Caught by `noMergeMutantIsDetected`,
  `failedBranchesMergeExpectationsAtFarthestPosition`, the permutation test and both
  generative suites (the scratch product edit failed 8 new tests).
- **Empty/partial-match guard removed** from `otherwise`
  (`MutationParsers.noEmptyGuardOtherwise`, and a scratch edit in `Parser.java`):
  `otherwise` collapses toward `or` and falls back after a partial match. Caught by
  `noEmptyGuardMutantIsDetected` and the token-level generative suite (the scratch
  product edit failed 3 new tests).

## Most dangerous counter-example

`or( atomic(seq(prefix, X)), atomic(seq(prefix, Y)), seq(prefix, Z) )` with every
alternative sharing `prefix`. It is the sharpest shape because three boundaries must hold
at the same time and a return-value-only test cannot see two of the three failures:

1. a branch that consumes `prefix` and then fails must **roll back** so a later branch
   can re-consume the prefix (else the choice is spuriously rejected);
2. the branch's error must survive at the **farthest** position even though the cursor
   was rewound and even if a later branch succeeds (the diagnostic points past the
   prefix, not at the start);
3. two branches failing at the same deep position with different terminals must
   **merge** both expected names, and `atomic` must not erase that evidence.

It is isolated in `SharedPrefixBacktrackingRegressionTest`:
`sharedPrefixStillAllowsLaterBranchToMatch` (rollback, against the no-rollback mutant),
`failedBranchesMergeExpectationsAtFarthestPosition` (farthest position + merged set,
against the no-merge mutant), `farthestErrorSurvivesEvenWhenChoiceSucceeds` (evidence
surviving a successful choice), and `atomicDoesNotEraseDeepErrorEvidence` (pinning the
behavior to the choice rather than to atomic suppressing diagnostics).

## Adjacent-semantics degradation guards

The boundary matrix and generative suites also pin behavior next to the mutated surface,
so a change that "fixes" one boundary cannot quietly regress a neighbor: atomic vs.
non-atomic deep-error equality, `peek` zero-width success and its restored cursor, `not`
frozen-error behavior and `unexpected` precedence, `optional`/`many` zero-width
termination, `expect` vs. missing precedence, tie-breaking of `longest`/`shortest`,
and the trailing `EOF` diagnostic position.

## Build/run notes

- New cases run individually, e.g.
  `mvn -q -pl jparsec surefire:test -Dtest=BacktrackingPropertyTest`, and with the full
  suite `mvn -q test`.
- The project's build pins Error Prone 2.0.15 on the compiler plugin, which cannot run on
  JDK 17; the build/test commands were executed with a JDK 8 toolchain (the project
  targets source/target 1.8). This is an environment/toolchain requirement, not a change
  to the repository.
- No random sleeps, network access, machine-specific paths or fixture-name special-casing
  are used; generation is seeded and inputs come only from the fixed `{a,b,x}` alphabet.
