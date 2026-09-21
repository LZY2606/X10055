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
package org.jparsec;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Property tests for parser choice, backtracking and error merging.
 *
 * <p>Every generated grammar/input pair is evaluated four ways:
 * <ol>
 *   <li>the reference interpreter ({@link MiniReference}),</li>
 *   <li>the character-level product parser in PRODUCTION mode,</li>
 *   <li>the character-level product parser in DEBUG mode (parse-tree trace),</li>
 *   <li>the token-level product parser driven directly on a {@link Token} array.</li>
 * </ol>
 * Equality is checked on {@link ParseEvidence}, i.e. on the full state boundary
 * (success, value, furthest error index, merged expected set, failure and
 * encountered token), never on the return value alone.
 */
@RunWith(Parameterized.class)
public class BacktrackingPropertyTest {

  private static final int RANDOM_GRAMMARS = 60;
  private static final int RANDOM_INPUTS_PER_GRAMMAR = 70;

  @Parameterized.Parameters(name = "mode={0}")
  public static List<Object[]> modes() {
    List<Object[]> modes = new ArrayList<>();
    modes.add(new Object[] {Parser.Mode.PRODUCTION});
    modes.add(new Object[] {Parser.Mode.DEBUG});
    return modes;
  }

  private final Parser.Mode mode;

  public BacktrackingPropertyTest(Parser.Mode mode) {
    this.mode = mode;
  }

  // ---- Template grammars on all three layouts -----------------------------

  @Test public void templatesAgreeWithReferenceOnAllLayouts() {
    for (GrammarGen.Case grammarCase : GrammarGen.templates()) {
      for (String input : allInputsFor(grammarCase)) {
        assertAllEnginesAgree(grammarCase, input);
      }
    }
  }

  // ---- Random grammars: the boundary matrix -------------------------------

  @Test public void randomGrammarsAgreeWithReferenceOnAllLayouts() {
    List<String> inputs = GrammarGen.randomInputs(RANDOM_INPUTS_PER_GRAMMAR);
    for (GrammarGen.Case grammarCase : GrammarGen.randomGrammars(RANDOM_GRAMMARS)) {
      for (String input : inputs) {
        assertAllEnginesAgree(grammarCase, input);
      }
    }
  }

  // ---- Permuting irrelevant branches must not change the evidence ---------

  @Test public void swappingFailingOrBranchesPreservesEvidence() {
    for (GrammarGen.Case grammarCase : GrammarGen.templates()) {
      for (String input : allInputsFor(grammarCase)) {
        MiniGrammar swap =
            BranchPermutation.swapFirstTwoFailingChildren(grammarCase.grammar, input);
        if (swap != null) {
          assertPermutationInvariant(grammarCase.grammar, swap, input);
        }
      }
    }
  }

  @Test public void swappingFailingOrBranchesPreservesEvidenceRandom() {
    for (GrammarGen.Case grammarCase : GrammarGen.randomGrammars(30)) {
      for (String input : GrammarGen.randomInputs(30)) {
        MiniGrammar swap = BranchPermutation.swapFirstTwoFailingChildren(grammarCase.grammar);
        if (swap != null) {
          assertPermutationInvariant(grammarCase.grammar, swap, input);
        }
        MiniGrammar swapBest = BranchPermutation.swapFirstTwoFailingBestChildren(grammarCase.grammar);
        if (swapBest != null) {
          assertPermutationInvariant(grammarCase.grammar, swapBest, input);
        }
      }
    }
  }

  // ---- Shared-prefix branches must keep the furthest error ----------------

  @Test public void furthestErrorSurvivesSharedPrefix() {
    GrammarGen.Case grammarCase = named("sharedPrefixOr");
    // First branch dies at index 2, second at index 1: index 2 must win and
    // be identical whether or not the branches are listed in reverse order.
    ParseEvidence packed = ParseEvidence.runChar(
        MiniGrammar.compileChar(grammarCase.grammar), "abx", mode);
    ParseEvidence swapped = ParseEvidence.runChar(
        MiniGrammar.compileChar(BranchPermutation.swapChildren(grammarCase.grammar, 0)),
        "abx", mode);
    assertFailure(packed, 2, java.util.Arrays.asList("c", "d"), "x");
    assertEqualsEvidence(packed, swapped);
  }

  @Test public void atomicBacktrackRepositionsButKeepsError() {
    GrammarGen.Case grammarCase = named("sharedPrefixAtomic");
    // Atomic branch consumes "ab" then fails on c; the input is rewound so the
    // second branch can fail at index 1, but the furthest error (index 2)
    // still reports "c".
    ParseEvidence evidence = ParseEvidence.runChar(
        MiniGrammar.compileChar(grammarCase.grammar), "abx", mode);
    assertFailure(evidence, 2, java.util.Arrays.asList("c"), "x");
  }

  @Test public void lookaheadDoesNotConsumeButRecordsError() {
    GrammarGen.Case grammarCase = named("lookaheadGating");
    ParseEvidence evidence = ParseEvidence.runChar(
        MiniGrammar.compileChar(grammarCase.grammar), "axbc", mode);
    // Peek fails at index 1 expecting b without consuming "a"; the whole
    // parse therefore fails at index 1.
    assertFailure(evidence, 1, java.util.Arrays.asList("b"), "x");
  }

  // ---- helpers ------------------------------------------------------------

  private void assertAllEnginesAgree(GrammarGen.Case grammarCase, String packedInput) {
    ParseEvidence reference = MiniReference.run(grammarCase.grammar, packedInput);

    ParseEvidence charEvidence = ParseEvidence.runChar(
        MiniGrammar.compileChar(grammarCase.grammar), packedInput, mode);
    assertEqualsEvidence(describe(grammarCase, packedInput, "char"), reference, charEvidence);

    for (MiniGrammarLayout layout : MiniGrammarLayout.values()) {
      String rendered = layout.render(packedInput);
      ParseEvidence tokenEvidence = ParseEvidence.runTokens(
          MiniGrammar.compileToken(grammarCase.grammar),
          rendered, layout.tokens(packedInput), mode);
      ParseEvidence translated = translate(reference, packedInput, layout);
      assertEqualsEvidence(
          describe(grammarCase, packedInput, "token:" + layout), translated, tokenEvidence);
    }
  }

  private void assertPermutationInvariant(
      MiniGrammar original, MiniGrammar permuted, String packedInput) {
    ParseEvidence before = MiniReference.run(original, packedInput);
    ParseEvidence after = MiniReference.run(permuted, packedInput);
    org.junit.Assert.assertTrue(
        "reference permutation changed evidence for " + packedInput,
        before.sameExceptExpectedOrder(after));

    ParseEvidence productBefore = ParseEvidence.runChar(
        MiniGrammar.compileChar(original), packedInput, mode);
    ParseEvidence productAfter = ParseEvidence.runChar(
        MiniGrammar.compileChar(permuted), packedInput, mode);
    org.junit.Assert.assertTrue(
        "branch permutation changed product evidence for " + packedInput
            + "\nbefore=" + productBefore + "\nafter =" + productAfter,
        productBefore.sameExceptExpectedOrder(productAfter));
  }

  /** Translates logical error positions to physical ones for a layout. */
  private static ParseEvidence translate(
      ParseEvidence logical, String packedInput, MiniGrammarLayout layout) {
    if (logical.success) {
      return logical;
    }
    int logicalPos = logical.errorIndex;
    int physical;
    String encountered;
    if (logicalPos >= packedInput.length()) {
      physical = layout.render(packedInput).length();
      encountered = ParseContext.EOF;
    } else {
      physical = layout.physicalIndex(packedInput, logicalPos);
      String spelling = MiniGrammarLayout.spelling(packedInput.charAt(logicalPos));
      encountered = String.valueOf(spelling.charAt(0));
    }
    return ParseEvidence.failure(
        physical, logical.expected, logical.unexpected, logical.failureMessage, encountered);
  }

  private GrammarGen.Case named(String name) {
    for (GrammarGen.Case grammarCase : GrammarGen.templates()) {
      if (grammarCase.name.equals(name)) return grammarCase;
    }
    throw new IllegalArgumentException(name);
  }

  /**
   * A boundary matrix of inputs: empty, single symbols, every prefix of the
   * interesting literals, and a handful of mixed strings with each symbol in
   * the error position.
   */
  private static List<String> allInputsFor(GrammarGen.Case grammarCase) {
    List<String> inputs = new ArrayList<>();
    inputs.add("");
    String[] seeds = {"a", "ab", "abc", "abd", "abx", "ax", "axbc", "abcd", "abca",
        "b", "x", "aa", "aaa", "aab", "xy", "bc", "abcc", "ad", "d", "abcx"};
    for (String seed : seeds) inputs.add(seed);
    return inputs;
  }

  private static String describe(GrammarGen.Case grammarCase, String input, String engine) {
    return grammarCase + " input=" + quote(input) + " engine=" + engine;
  }

  private static String quote(String input) {
    return "\"" + input + "\"";
  }

  private static void assertFailure(
      ParseEvidence evidence, int index, List<String> expectedSet, String encountered) {
    org.junit.Assert.assertFalse("expected failure but got " + evidence, evidence.success);
    org.junit.Assert.assertEquals(
        "error index in " + evidence, index, evidence.errorIndex);
    org.junit.Assert.assertEquals(
        "expected set in " + evidence, expectedSet, evidence.expected);
    org.junit.Assert.assertEquals(
        "encountered in " + evidence, encountered, evidence.encountered);
  }

  private static void assertEqualsEvidence(ParseEvidence expected, ParseEvidence actual) {
    if (!expected.equals(actual)) {
      org.junit.Assert.fail("\nexpected: " + expected + "\nactual:   " + actual);
    }
  }

  private static void assertEqualsEvidence(String context, ParseEvidence expected, ParseEvidence actual) {
    if (!expected.equals(actual)) {
      org.junit.Assert.fail(
          context + "\nexpected: " + expected + "\nactual:   " + actual);
    }
  }
}
