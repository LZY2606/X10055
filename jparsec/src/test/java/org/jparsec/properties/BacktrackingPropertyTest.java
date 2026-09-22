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
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  * See the License for the specific language governing permissions and       *
 * limitations under the License.                                            *
 *****************************************************************************/
package org.jparsec.properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Property tests for parser-combinator backtracking, longest/shortest choice, and the
 * merged farthest-error record.
 *
 * <p>Each generated (or hand-built) grammar is evaluated twice: by {@link ReferenceMachine}
 * (the oracle) and by the real jparsec combinators in {@link JParsecMachine}. The compared
 * evidence is the full {@link Outcome} (success, physical position, expected set / failure
 * message), never just the returned value.
 */
public class BacktrackingPropertyTest {

  private static final int CASES = 3000;

  // ---------------------------------------------------------------------------
  // Property 1: reference interpreter agreement, character level.
  // ---------------------------------------------------------------------------

  @Test
  public void charLevelAgreesWithReference() {
    int checked = 0;
    for (long seed = 1; seed <= CASES; seed++) {
      Gen gen = new Gen(seed);
      G grammar = gen.grammar();
      List<String> symbols = gen.symbols(6);
      Outcome expected = ReferenceMachine.run(grammar, symbols);
      Outcome actual = JParsecMachine.run(grammar, Gen.charSource(symbols),
          JParsecMachine.Level.CHAR);
      assertOutcome("char-level seed=" + seed + " grammar=" + G.show(grammar)
              + " source=" + Gen.charSource(symbols),
          expected, actual);
      checked++;
    }
    assertTrue("sanity: generated cases actually ran", checked > 0);
  }

  // ---------------------------------------------------------------------------
  // Property 2: token level reports the same expected set and the physical
  // source position. The oracle is run in token-level mode, because the
  // physical/logical coordinates diverge around otherwise (documented in
  // ReferenceMachine).
  // ---------------------------------------------------------------------------

  @Test
  public void tokenLevelPositionsMapToSameSourceLocation() {
    int failures = 0;
    for (long seed = 1; seed <= CASES; seed++) {
      Gen gen = new Gen(seed * 7 + 3);
      G grammar = gen.grammar();
      List<String> symbols = gen.symbols(6);
      Outcome reference = ReferenceMachine.runToken(grammar, symbols);
      Outcome tokenOutcome = JParsecMachine.run(grammar, Gen.tokenSource(symbols),
          JParsecMachine.Level.TOKEN);
      if (!reference.equals(tokenOutcome)) {
        failures++;
        assertEquals("token-level seed=" + seed + " grammar=" + G.show(grammar)
                + " source=" + Gen.tokenSource(symbols),
            reference, tokenOutcome);
      }
    }
    assertEquals("unexpected token/char disagreement", 0, failures);
  }

  // ---------------------------------------------------------------------------
  // Property 2b: away from the combinators that compare PHYSICAL length against
  // a LOGICAL cursor (longest/shortest and otherwise), a one-token-per-character
  // lexer must reproduce exactly the character-level error positions. This is
  // the strong token/char alignment guarantee for or/atomic/peek/not/optional.
  // ---------------------------------------------------------------------------

  @Test
  public void tokenAndCharPositionsAgreeWithoutOtherwise() {
    for (long seed = 1; seed <= 800; seed++) {
      Gen gen = new Gen(seed * 13 + 11);
      G grammar = logicalChoicesOnly(gen.grammar());
      List<String> symbols = gen.symbols(6);
      Outcome charOutcome = JParsecMachine.run(grammar, Gen.charSource(symbols),
          JParsecMachine.Level.CHAR);
      Outcome tokenOutcome = JParsecMachine.run(grammar, Gen.tokenSource(symbols),
          JParsecMachine.Level.TOKEN);
      // Same symbols, but the spaced token source maps slot i to source index 2i
      // (and success consumes the whole spaced string).
      Outcome charInTokenCoordinates = toSpacedCoordinates(charOutcome, symbols.size());
      assertEquals("token/char alignment seed=" + seed + " grammar=" + G.show(grammar)
              + " sources=" + Gen.charSource(symbols) + "/" + Gen.tokenSource(symbols),
          charInTokenCoordinates, tokenOutcome);
    }
  }

  private static Outcome toSpacedCoordinates(Outcome charOutcome, int symbolCount) {
    if (charOutcome.success) {
      return Outcome.succeeded(symbolCount == 0 ? 0 : symbolCount * 2 - 1);
    }
    int physical = charOutcome.position >= symbolCount
        ? Math.max(0, symbolCount * 2 - 1)
        : charOutcome.position * 2;
    if (charOutcome.errorKind == Outcome.Kind.FAILURE) {
      return Outcome.failure(physical, charOutcome.failureMessage);
    }
    if (charOutcome.errorKind == Outcome.Kind.UNEXPECTED) {
      return Outcome.unexpected(physical, charOutcome.failureMessage);
    }
    return Outcome.expecting(physical, charOutcome.expected);
  }

  /**
   * Rewrites the otherwise-affecting nodes ({@code Best} and {@code Otherwise})
   * into plain ordered choice, used only by the token/char alignment property.
   */
  private static G logicalChoicesOnly(G g) {
    if (g instanceof G.Lit || g instanceof G.Empty
        || g instanceof G.Fail || g instanceof G.Expect) {
      return g;
    }
    if (g instanceof G.Seq) {
      List<G> cs = new ArrayList<>();
      for (G c : ((G.Seq) g).children) cs.add(logicalChoicesOnly(c));
      return new G.Seq(cs);
    }
    if (g instanceof G.Or) {
      List<G> cs = new ArrayList<>();
      for (G c : ((G.Or) g).children) cs.add(logicalChoicesOnly(c));
      return new G.Or(cs);
    }
    if (g instanceof G.Best) {
      List<G> cs = new ArrayList<>();
      for (G c : ((G.Best) g).children) cs.add(logicalChoicesOnly(c));
      return new G.Or(cs);
    }
    if (g instanceof G.Atomic) return G.atomic(logicalChoicesOnly(((G.Atomic) g).child));
    if (g instanceof G.Peek) return G.peek(logicalChoicesOnly(((G.Peek) g).child));
    if (g instanceof G.Not) return G.not(logicalChoicesOnly(((G.Not) g).child));
    if (g instanceof G.Optional) return G.optional(logicalChoicesOnly(((G.Optional) g).child));
    if (g instanceof G.Many) return G.many(logicalChoicesOnly(((G.Many) g).child));
    // otherwise(a,b) -> or(a,b) for the alignment check.
    if (g instanceof G.Otherwise) {
      return new G.Or(java.util.Arrays.asList(
          logicalChoicesOnly(((G.Otherwise) g).primary),
          logicalChoicesOnly(((G.Otherwise) g).fallback)));
    }
    throw new AssertionError("unknown node " + g.getClass());
  }

  // ---------------------------------------------------------------------------
  // Property 3: swapping branches that can never change the result leaves both
  // the choice and the merged error record untouched.
  // ---------------------------------------------------------------------------

  @Test
  public void permutingEquivalentBranchesIsInvisible() {
    // Identical branches: order among duplicates is irrelevant.
    checkPermutationInvisible(G.or(G.lit("a"), G.lit("a")),
        Collections.singletonList("a"));
    checkPermutationInvisible(G.longest(G.lit("a"), G.lit("a")),
        Collections.singletonList("a"));
    // Two distinct failing branches that fail at the same position: the merged
    // expected set must be order-independent, and so must the error position.
    checkPermutationInvisible(G.or(G.lit("a"), G.lit("b")),
        Collections.singletonList("x"));
    checkPermutationInvisible(G.longest(G.lit("a"), G.lit("b")),
        Collections.singletonList("x"));
    checkPermutationInvisible(G.shortest(G.lit("a"), G.lit("b")),
        Collections.singletonList("x"));
    // A deep failure point after a shared prefix: both branches carry the error
    // to the same physical position regardless of order.
    G left = G.seq(G.lit("a"), G.lit("b"), G.lit("x"));
    G right = G.seq(G.lit("a"), G.lit("b"), G.lit("a"));
    checkPermutationInvisible(G.or(left, right), toList("a", "b", "b"));
    checkPermutationInvisible(G.longest(left, right), toList("a", "b", "b"));

    // Generated equivalent branch groups.
    for (long seed = 1; seed <= 200; seed++) {
      Gen gen = new Gen(seed * 31 + 5);
      G branch = gen.grammar();
      G duplicateChoice = new Gen(seed).range(2) == 0
          ? G.or(branch, branch)
          : G.longest(branch, branch);
      List<String> input = gen.symbols(6);
      checkPermutationInvisible(duplicateChoice, input);
    }
  }

  private void checkPermutationInvisible(G choice, List<String> symbols) {
    Outcome first = ReferenceMachine.run(choice, symbols);
    G swapped = swapBranches(choice);
    Outcome second = ReferenceMachine.run(swapped, symbols);
    Outcome jparsecFirst = JParsecMachine.run(choice, Gen.charSource(symbols),
        JParsecMachine.Level.CHAR);
    Outcome jparsecSecond = JParsecMachine.run(swapped, Gen.charSource(symbols),
        JParsecMachine.Level.CHAR);
    assertEquals("reference changed under branch permutation: " + G.show(choice)
            + " source=" + symbols, first, second);
    assertEquals("jparsec changed under branch permutation: " + G.show(choice)
            + " source=" + symbols, jparsecFirst, jparsecSecond);
    assertOutcome("jparsec/reference disagreement: " + G.show(choice)
            + " source=" + symbols, first, jparsecFirst);
  }

  private static G swapBranches(G choice) {
    List<G> children = new ArrayList<>(branchesOf(choice));
    assertTrue("permutation needs >= 2 branches", children.size() >= 2);
    Collections.swap(children, 0, 1);
    if (choice instanceof G.Or) return new G.Or(children);
    G.Best best = (G.Best) choice;
    return new G.Best(children, best.longest);
  }

  private static List<G> branchesOf(G choice) {
    if (choice instanceof G.Or) return ((G.Or) choice).children;
    return ((G.Best) choice).children;
  }

  // ---------------------------------------------------------------------------
  // Property 4 (boundary matrix): hand-fixed truth table for the four choice
  // mechanisms over shared-prefix, nullable and deeply-failing grammars.
  // ---------------------------------------------------------------------------

  @Test
  public void boundaryMatrix() {
    // or: first success wins even when a later branch is longer.
    row(G.or(G.lit("a"), G.seq(G.lit("a"), G.lit("b"))), "ab",
        expecting(1, "EOF"));
    // longest: longer branch wins and consumes the prefix.
    row(G.longest(G.lit("a"), G.seq(G.lit("a"), G.lit("b"))), "ab",
        success(2));
    // shortest: shorter branch wins.
    row(G.shortest(G.lit("a"), G.seq(G.lit("a"), G.lit("b"))), "ab",
        expecting(1, "EOF"));
    // nullable first branch makes or return immediately at position 0.
    row(G.or(G.empty(), G.lit("a")), "a", expecting(0, "EOF"));
    // longest still prefers the consuming branch over the empty one.
    row(G.longest(G.empty(), G.lit("a")), "a", success(1));
    // all branches fail at the start: merged expected set.
    row(G.or(G.lit("a"), G.lit("b")), "x",
        expecting(0, "a", "b"));
    // failures after a shared prefix: farthest position wins and deep
    // expectations merge; the start-of-input alternatives are discarded
    // as less relevant.
    row(G.or(
            G.seq(G.lit("a"), G.lit("b")),
            G.seq(G.lit("a"), G.lit("x"))),
        "aa", expecting(1, "b", "x"));
    // atomic rolls the consumed prefix back, so or can try the next branch;
    // without atomic the choice would still backtrack, but the following
    // combinator in the row below proves the step/cursor restore matters.
    row(G.or(
            G.atomic(G.seq(G.lit("a"), G.lit("b"))),
            G.seq(G.lit("a"), G.lit("x"))),
        "ax", success(2));
    // nested atomic inside longest: the failed branch leaves no consumed
    // prefix, and the winning branch's length is compared from position 0.
    row(G.longest(
            G.atomic(G.seq(G.lit("a"), G.lit("b"), G.lit("a"))),
            G.seq(G.lit("a"), G.lit("x"))),
        "axa", expecting(2, "EOF"));
    // negative lookahead failure preserves the starting cursor.
    row(G.seq(G.not(G.lit("a")), G.lit("b")), "b", success(1));
    // negative lookahead that fires reports "unexpected" at the cursor and wins
    // over no other error.
    row(G.seq(G.not(G.lit("a")), G.lit("b")), "ab",
        Outcome.unexpected(0, "a"));
    // lookahead succeeds without consuming.
    row(G.seq(G.peek(G.lit("a")), G.lit("a")), "a", success(1));
    // multiple failure points across nested choices collapse to the farthest.
    row(G.or(
            G.or(G.lit("a"), G.seq(G.lit("b"), G.lit("a"))),
            G.seq(G.lit("b"), G.lit("x"))),
        "bb", expecting(1, "a", "x"));
    // expect() outranks missing-terminal errors at the same position.
    row(G.or(G.lit("a"), G.expect("keyword")), "x",
        expecting(0, "keyword"));
    // trailing input after a successful parse is an EOF error at the end.
    row(G.lit("a"), "ab", expecting(1, "EOF"));
  }

  private void row(G grammar, String source, Outcome expected) {
    List<String> symbols = toList(source.split(""));
    Outcome reference = ReferenceMachine.run(grammar, symbols);
    Outcome actual = JParsecMachine.run(grammar, source, JParsecMachine.Level.CHAR);
    assertEquals("truth-table row grammar=" + G.show(grammar) + " source=" + source,
        expected, reference);
    assertOutcome("truth-table row grammar=" + G.show(grammar) + " source=" + source,
        expected, actual);
  }

  // ---------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------

  private static List<String> toList(String... ss) {
    List<String> out = new ArrayList<>();
    Collections.addAll(out, ss);
    return out;
  }

  private static Outcome success(int position) {
    return Outcome.succeeded(position);
  }

  private static Outcome expecting(int position, String... expected) {
    java.util.Set<String> set = new java.util.LinkedHashSet<>();
    Collections.addAll(set, expected);
    return Outcome.expecting(position, set);
  }

  private static void assertOutcome(String context, Outcome expected, Outcome actual) {
    assertEquals(context, expected, actual);
  }
}
