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
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Deterministic generators for the property tests.
 *
 * <p>Two kinds of inputs are produced:
 * <ul>
 *   <li><b>Seeded templates</b> - hand-shaped grammars that guarantee every
 *       dangerous pattern: branches sharing a prefix, nullable
 *       ({@code opt}/{@code many}/empty) alternatives, nested atomic
 *       backtracking, lookahead, and several failure points at different
 *       depths.</li>
 *   <li><b>Random grammars</b> - bounded-depth recursive generation over the
 *       same alphabet, exercised against many packed input strings, to stress
 *       the state machine beyond the templates.</li>
 * </ul>
 * The RNG is seeded with a fixed constant, so failures are reproducible and
 * the offending grammar/input pair is reported directly.
 */
final class GrammarGen {

  static final long FIXED_SEED = 20260922L;

  private GrammarGen() {}

  static final class Case {
    final String name;
    final MiniGrammar grammar;

    Case(String name, MiniGrammar grammar) {
      this.name = name;
      this.grammar = grammar;
    }

    @Override public String toString() { return name; }
  }

  // ---- Hand-shaped dangerous templates ------------------------------------

  static List<Case> templates() {
    return Arrays.asList(
        new Case("sharedPrefixOr", fully(MiniGrammar.or(
            MiniGrammar.seq(MiniGrammar.lit('a'), MiniGrammar.lit('b'), MiniGrammar.lit('c')),
            MiniGrammar.seq(MiniGrammar.lit('a'), MiniGrammar.lit('b'), MiniGrammar.lit('d'))))),

        new Case("sharedPrefixAtomic", fully(MiniGrammar.or(
            MiniGrammar.atomic(MiniGrammar.seq(
                MiniGrammar.lit('a'), MiniGrammar.lit('b'), MiniGrammar.lit('c'))),
            MiniGrammar.seq(MiniGrammar.lit('a'), MiniGrammar.lit('x'))))),

        new Case("nullableBranches", fully(MiniGrammar.or(
            MiniGrammar.seq(MiniGrammar.lit('a'),
                MiniGrammar.opt(MiniGrammar.lit('b')), MiniGrammar.lit('c')),
            MiniGrammar.seq(MiniGrammar.many(MiniGrammar.lit('a')), MiniGrammar.lit('x')),
            MiniGrammar.eps()))),

        new Case("nestedBacktrack", fully(MiniGrammar.or(
            MiniGrammar.atomic(MiniGrammar.seq(
                MiniGrammar.lit('a'),
                MiniGrammar.or(
                    MiniGrammar.atomic(MiniGrammar.seq(
                        MiniGrammar.lit('b'), MiniGrammar.lit('c'), MiniGrammar.lit('d'))),
                    MiniGrammar.seq(MiniGrammar.lit('b'), MiniGrammar.lit('x'))),
                MiniGrammar.lit('d'))),
            MiniGrammar.seq(MiniGrammar.lit('a'), MiniGrammar.lit('b'))))),

        new Case("lookaheadGating", fully(MiniGrammar.seq(
            MiniGrammar.peek(MiniGrammar.seq(MiniGrammar.lit('a'), MiniGrammar.lit('b'))),
            MiniGrammar.many(MiniGrammar.lit('a')),
            MiniGrammar.lit('b'),
            MiniGrammar.lit('c')))),

        new Case("longestChoice", fully(MiniGrammar.longest(
            MiniGrammar.seq(MiniGrammar.lit('a'), MiniGrammar.lit('b')),
            MiniGrammar.seq(MiniGrammar.lit('a'), MiniGrammar.lit('b'),
                MiniGrammar.opt(MiniGrammar.lit('c'))),
            MiniGrammar.lit('a')))),

        new Case("shortestChoice", fully(MiniGrammar.shortest(
            MiniGrammar.seq(MiniGrammar.lit('a'), MiniGrammar.lit('b')),
            MiniGrammar.seq(MiniGrammar.lit('a'), MiniGrammar.lit('b'), MiniGrammar.lit('c')),
            MiniGrammar.lit('a')))),

        new Case("multipleFailurePoints", fully(MiniGrammar.seq(
            MiniGrammar.lit('a'),
            MiniGrammar.or(
                MiniGrammar.label(MiniGrammar.seq(
                    MiniGrammar.lit('b'), MiniGrammar.lit('c')), "bc"),
                MiniGrammar.seq(MiniGrammar.lit('b'), MiniGrammar.lit('d'))),
            MiniGrammar.lit('x')))),

        new Case("labeledMerge", fully(MiniGrammar.or(
            MiniGrammar.label(MiniGrammar.lit('a'), "alpha"),
            MiniGrammar.label(MiniGrammar.lit('b'), "beta"),
            MiniGrammar.lit('c')))),

        new Case("failDeep", fully(MiniGrammar.or(
            MiniGrammar.atomic(MiniGrammar.seq(MiniGrammar.lit('a'),
                MiniGrammar.or(MiniGrammar.fail("boom"), MiniGrammar.lit('b')))),
            MiniGrammar.seq(MiniGrammar.lit('a'), MiniGrammar.lit('x'))))),

        new Case("manyOrEmpty", fully(MiniGrammar.many(
            MiniGrammar.or(MiniGrammar.lit('a'), MiniGrammar.lit('b'))))),

        new Case("emptyInLongestTie", fully(MiniGrammar.longest(
            MiniGrammar.eps(),
            MiniGrammar.seq(MiniGrammar.lit('a'), MiniGrammar.lit('b')),
            MiniGrammar.lit('a')))),

        new Case("atomicMany", fully(MiniGrammar.or(
            MiniGrammar.atomic(MiniGrammar.seq(
                MiniGrammar.many(MiniGrammar.lit('a')), MiniGrammar.lit('b'))),
            MiniGrammar.many(MiniGrammar.lit('a'))))),
    );
  }

  // ---- Random grammars ----------------------------------------------------

  static List<Case> randomGrammars(int count) {
    Random random = new Random(FIXED_SEED);
    List<Case> cases = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      MiniGrammar grammar = fully(randomNode(random, 3));
      cases.add(new Case("random#" + i, grammar));
    }
    return cases;
  }

  private static MiniGrammar randomNode(Random random, int depth) {
    int roll = random.nextInt(100);
    if (depth <= 0 || roll < 32) {
      return MiniGrammar.lit(MiniGrammar.SYMBOLS[random.nextInt(MiniGrammar.SYMBOLS.length)]);
    }
    if (roll < 40) return MiniGrammar.eps();
    if (roll < 48) return MiniGrammar.lit(MiniGrammar.SYMBOLS[random.nextInt(MiniGrammar.SYMBOLS.length)]);
    if (roll < 62) {
      int n = 2 + random.nextInt(2);
      List<MiniGrammar> children = randomChildren(random, depth, n);
      return new MiniGrammar.Seq(children);
    }
    if (roll < 78) {
      int n = 2 + random.nextInt(2);
      return new MiniGrammar.Or(randomChildren(random, depth, n));
    }
    if (roll < 88) {
      int n = 2 + random.nextInt(2);
      return new MiniGrammar.Best(randomChildren(random, depth, n), random.nextBoolean());
    }
    if (roll < 93) return new MiniGrammar.Opt(randomNode(random, depth - 1));
    if (roll < 96) return new MiniGrammar.Many(randomNode(random, depth - 1));
    if (roll < 98) return new MiniGrammar.Atomic(randomNode(random, depth - 1));
    if (roll < 99) return new MiniGrammar.Peek(randomNode(random, depth - 1));
    return new MiniGrammar.Label(
        randomNode(random, depth - 1), "L" + (char) ('a' + random.nextInt(3)));
  }

  private static List<MiniGrammar> randomChildren(Random random, int depth, int n) {
    List<MiniGrammar> children = new ArrayList<>();
    for (int i = 0; i < n; i++) children.add(randomNode(random, depth - 1));
    return children;
  }

  /** Packed logical strings over the alphabet, including EOF-prefix cases. */
  static List<String> randomInputs(int count) {
    Random random = new Random(FIXED_SEED ^ 0x5DEECE66DL);
    List<String> inputs = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      int len = random.nextInt(6);
      StringBuilder builder = new StringBuilder();
      for (int j = 0; j < len; j++) {
        builder.append(MiniGrammar.SYMBOLS[random.nextInt(MiniGrammar.SYMBOLS.length)]);
      }
      inputs.add(builder.toString());
    }
    return inputs;
  }
}
