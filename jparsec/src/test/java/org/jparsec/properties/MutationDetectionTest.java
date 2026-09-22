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

import static org.junit.Assert.assertFalse;

import java.util.Collections;
import java.util.List;

import org.jparsec.MutationParsers;
import org.jparsec.Parser;
import org.junit.Test;

/**
 * Proves the new property cases have teeth: three single-boundary mutants are assembled from
 * {@link MutationParsers} and each must disagree with the reference oracle on a targeted
 * regression grammar. The same grammar run through the production combinators must agree with
 * the oracle, pinning down that the disagreement comes from the deleted boundary alone.
 */
public class MutationDetectionTest {

  private static final JParsecMachine.Level CHAR = JParsecMachine.Level.CHAR;

  // ---------------------------------------------------------------------------
  // Mutant 1: no position rollback between alternatives.
  // ---------------------------------------------------------------------------

  @Test
  public void noRollbackMutantIsDetected() {
    // or(seq(a,b), seq(a,x)) on "ax": production backtracks the consumed "a" of
    // branch 1 and branch 2 matches "ax". The mutant leaves the cursor at index 1
    // after branch 1, so branch 2 requires another "a" and fails; the whole parse
    // fails at the merged-farthest position instead of succeeding.
    G branch1 = G.seq(G.lit("a"), G.lit("b"));
    G branch2 = G.seq(G.lit("a"), G.lit("x"));
    G grammar = G.or(branch1, branch2);
    String source = "ax";

    Outcome oracle = ReferenceMachine.run(grammar, symbols(source));
    Outcome production = JParsecMachine.run(grammar, source, CHAR);
    org.junit.Assert.assertEquals(oracle, production);

    Parser<String> mutant = MutationParsers.noRollbackOr(
        JParsecMachine.parserOf(branch1, CHAR),
        JParsecMachine.parserOf(branch2, CHAR));
    Outcome mutated = JParsecMachine.runParser(mutant, source, CHAR);
    assertFalse("no-rollback mutant should be caught on " + source,
        oracle.equals(mutated));
  }

  // ---------------------------------------------------------------------------
  // Mutant 2: no error merging between alternatives.
  // ---------------------------------------------------------------------------

  @Test
  public void noMergeMutantIsDetected() {
    // or(lit(a), lit(b)) on "x": both alternatives fail at index 0 and production
    // merges them into {a,b}. The mutant suppresses the first branch's error, so
    // only {b} survives.
    G litA = G.lit("a");
    G litB = G.lit("b");
    G grammar = G.or(litA, litB);
    String source = "x";

    Outcome oracle = ReferenceMachine.run(grammar, symbols(source));
    Outcome production = JParsecMachine.run(grammar, source, CHAR);
    org.junit.Assert.assertEquals(oracle, production);
    org.junit.Assert.assertTrue(oracle.expected.contains("a"));
    org.junit.Assert.assertTrue(oracle.expected.contains("b"));

    Parser<String> mutant = MutationParsers.noMergeOr(
        JParsecMachine.parserOf(litA, CHAR),
        JParsecMachine.parserOf(litB, CHAR));
    Outcome mutated = JParsecMachine.runParser(mutant, source, CHAR);
    assertFalse("no-merge mutant should be caught on " + source,
        oracle.equals(mutated));
  }

  // ---------------------------------------------------------------------------
  // Mutant 3: otherwise without the empty/zero-width failure guard.
  // ---------------------------------------------------------------------------

  @Test
  public void noEmptyGuardMutantIsDetected() {
    // otherwise(seq(a,b,x), seq(a,b)) on "ab": the primary consumes "ab" and fails
    // at index 2, so production treats the partial match as fatal. The mutant always
    // falls back and the second branch matches "ab" completely, which is a
    // different parse outcome (success rather than failure at index 2).
    G primary = G.seq(G.lit("a"), G.lit("b"), G.lit("x"));
    G fallback = G.seq(G.lit("a"), G.lit("b"));
    G grammar = G.otherwise(primary, fallback);
    String source = "ab";

    Outcome oracle = ReferenceMachine.run(grammar, symbols(source));
    Outcome production = JParsecMachine.run(grammar, source, CHAR);
    org.junit.Assert.assertEquals(oracle, production);

    Parser<String> mutant = MutationParsers.noEmptyGuardOtherwise(
        JParsecMachine.parserOf(primary, CHAR),
        JParsecMachine.parserOf(fallback, CHAR));
    Outcome mutated = JParsecMachine.runParser(mutant, source, CHAR);
    assertFalse("no-empty-guard mutant should be caught on " + source,
        oracle.equals(mutated));
  }

  private static List<String> symbols(String source) {
    List<String> out = new java.util.ArrayList<>();
    for (char c : source.toCharArray()) out.add(String.valueOf(c));
    return Collections.unmodifiableList(out);
  }
}
