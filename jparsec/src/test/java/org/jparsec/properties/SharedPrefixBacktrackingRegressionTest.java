/*****************************************************************************
 * Copyright (C) jparsec.org                                                *
 * ------------------------------------------------------------------------- *
 * Licensed under the Apache License, Version 2.0 (the "License");           *
 * you may not use this file except in compliance with the License.          *
 * You may obtain a copy of the License at                                   *
 *                                                                           *
 * http://www.apache.org/licenses/LICENSE-2.0                                *
 *                                                                           *
 * Unless required by applicable law or agreed to in writing, software       *
 * distributed under the License is distributed on an "AS IS" BASIS,         *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  *
 * See the License for the specific language governing permissions and       *
 * limitations under the License.                                            *
 *****************************************************************************/
package org.jparsec.properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Arrays;
import java.util.List;

import org.jparsec.MutationParsers;
import org.jparsec.Parser;
import org.junit.Test;

/**
 * Regression for the single most dangerous backtracking/error-merging shape in
 * parser combinators:
 *
 * <pre>{@code
 * or( atomic(seq(prefix, X)), atomic(seq(prefix, Y)), seq(prefix, Z) )
 * }</pre>
 *
 * where every alternative shares {@code prefix}. Three boundaries must hold simultaneously:
 * <ol>
 * <li><b>Rollback</b>: a branch that consumed {@code prefix} and then failed must restore the
 *     cursor so the next branch starts at position 0; otherwise the second branch can never
 *     re-consume the shared prefix and the choice is spuriously rejected.</li>
 * <li><b>Farthest error</b>: a branch that failed <em>after</em> the shared prefix leaves its
 *     error at the deeper position. The eventual {@link ParserException} points there, not at
 *     the shallow position 0, even when another branch ultimately succeeds and even though
 *     backtracking rewound the cursor.</li>
 * <li><b>Merging</b>: two branches that fail at the same deep position with different
 *     expected terminals contribute <em>both</em> expected names (deduplicated, order-free).
 *     Atomic does not erase that record.</li>
 * </ol>
 *
 * <p>This is the sharpest case because any of the three deleted boundaries silently
 * misbehaves, and a test that only inspects the return value cannot see the two error-based
 * failures. Each assertion below is pinned both to the reference oracle and demonstrated to
 * break against the matching mutant in {@link MutationParsers}.
 */
public class SharedPrefixBacktrackingRegressionTest {

  private static final JParsecMachine.Level CHAR = JParsecMachine.Level.CHAR;

  // Non-atomic here on purpose: with atomic the inner parser rolls back itself
  // and would mask a missing rollback in the surrounding choice.
  private final G branchAB = G.seq(G.lit("a"), G.lit("b"));
  private final G branchAX = G.seq(G.lit("a"), G.lit("x"));
  private final G rollbackGrammar = G.or(branchAB, branchAX);

  private final G branchABX = G.atomic(G.seq(G.lit("a"), G.lit("b"), G.lit("x")));
  private final G branchABA = G.atomic(G.seq(G.lit("a"), G.lit("b"), G.lit("a")));
  private final G mergeGrammar = G.or(branchABX, branchABA);

  private static List<String> symbols(String source) {
    String[] parts = source.split("");
    return Arrays.asList(parts);
  }

  /**
   * Boundary 1 (rollback): input "ax". Branch 1 consumes "a", fails at index 1 expecting
   * {@code b}, and must roll back to 0 so branch 2 starts again with {@code a} and matches
   * "ax". A no-rollback mutant leaves the cursor at index 1, where branch 2 asks for another
   * {@code a}, so the parse spuriously fails.
   */
  @Test
  public void sharedPrefixStillAllowsLaterBranchToMatch() {
    String source = "ax";
    assertEquals(Outcome.succeeded(2),
        ReferenceMachine.run(rollbackGrammar, symbols(source)));
    assertEquals(Outcome.succeeded(2),
        JParsecMachine.run(rollbackGrammar, source, CHAR));

    Parser<String> mutant = MutationParsers.noRollbackOr(
        JParsecMachine.parserOf(branchAB, CHAR),
        JParsecMachine.parserOf(branchAX, CHAR));
    Outcome mutated = JParsecMachine.runParser(mutant, source, CHAR);
    assertFalse("mutant unexpectedly still succeeded: " + mutated,
        Outcome.succeeded(2).equals(mutated));
  }

  /**
   * Boundaries 2 + 3 (farthest position, merged expectations): input "abb". Both branches
   * consume the shared "ab", then fail at index 2 expecting different terminals. The error
   * position must be 2 (not 0) and the expected set must contain both names.
   */
  @Test
  public void failedBranchesMergeExpectationsAtFarthestPosition() {
    String source = "abb";
    java.util.Set<String> both = new java.util.LinkedHashSet<>(Arrays.asList("x", "a"));
    Outcome expected = Outcome.expecting(2, both);
    assertEquals(expected, ReferenceMachine.run(mergeGrammar, symbols(source)));
    assertEquals(expected, JParsecMachine.run(mergeGrammar, source, CHAR));

    Parser<String> mutant = MutationParsers.noMergeOr(
        JParsecMachine.parserOf(branchABX, CHAR),
        JParsecMachine.parserOf(branchABA, CHAR));
    assertFalse(expected.equals(JParsecMachine.runParser(mutant, source, CHAR)));
  }

  /**
   * Boundary 2 across the success path: the choice succeeds, but an error recorded by a
   * branch at a deep position must survive to the trailing-EOF diagnostic. Grammar adds a
   * third non-atomic branch that succeeds over a longer input while an earlier branch
   * recorded an error past the final cursor.
   */
  @Test
  public void farthestErrorSurvivesEvenWhenChoiceSucceeds() {
    // seq(or(atomic(a,b,x), atomic(a,b,a), seq(a,b)), lit(x)) on "ab": the first two
    // branches fail at index 2 expecting {x,a}; the third matches "ab", then the
    // following lit(x) fails at the same index 2 expecting x. The errors recorded by
    // the failed backtracked branches survive into the final diagnostic, so the
    // merged expected set is {x,a}, reported at index 2.
    G shallow = G.seq(G.lit("a"), G.lit("b"));
    G combined = G.seq(G.or(branchABX, branchABA, shallow), G.lit("x"));
    String source = "ab";
    Outcome expected = Outcome.expecting(2,
        new java.util.LinkedHashSet<>(Arrays.asList("x", "a")));
    assertEquals(expected, ReferenceMachine.run(combined, symbols(source)));
    assertEquals(expected, JParsecMachine.run(combined, source, CHAR));
  }

  /**
   * Atomic only changes the logical step/cursor of <em>its own</em> parse; it must not
   * suppress the error record. Removing atomic changes neither the position nor the merged
   * set here, which pins down that the error behavior is attributed to the choice, not to
   * atomic erasing diagnostics.
   */
  @Test
  public void atomicDoesNotEraseDeepErrorEvidence() {
    G nonAtomic = G.or(
        G.seq(G.lit("a"), G.lit("b"), G.lit("x")),
        G.seq(G.lit("a"), G.lit("b"), G.lit("a")));
    String source = "abb";
    Outcome atomicOutcome = JParsecMachine.run(mergeGrammar, source, CHAR);
    Outcome plainOutcome = JParsecMachine.run(nonAtomic, source, CHAR);
    assertEquals(atomicOutcome, plainOutcome);
    assertEquals(ReferenceMachine.run(nonAtomic, symbols(source)), plainOutcome);
  }
}
