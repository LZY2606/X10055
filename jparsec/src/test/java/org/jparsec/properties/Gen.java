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

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, seedable generator of small {@link G} grammars and input strings.
 *
 * <p>Generation is biased toward the state-boundary surface under test: choices
 * ({@code or}/{@code longest}/{@code shortest}) are common, branches frequently share a
 * leading literal, some branches are nullable ({@code empty}, {@code optional},
 * {@code peek}), and failure points ({@code fail}/{@code expect}/wrong literals) are
 * nested inside {@code atomic} or behind consumed prefixes. A simple linear congruential
 * generator is used so every counter-example is exactly replayable from its seed.
 */
final class Gen {

  /** Single-letter symbols so one literal == one input symbol at both parser levels. */
  static final String[] ALPHABET = {"a", "b", "x"};

  private long state;

  Gen(long seed) {
    this.state = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
  }

  int nextInt() {
    state = (state * 6364136223846793005L + 1442695040888963407L) & 0x7fffffffffffffffL;
    return (int) (state >>> 33);
  }

  int range(int bound) {
    return bound == 0 ? 0 : nextInt() % bound;
  }

  String symbol() {
    return ALPHABET[range(ALPHABET.length)];
  }

  /** Random input over the alphabet (possibly empty). */
  List<String> symbols(int maxLength) {
    int len = range(maxLength + 1);
    List<String> out = new ArrayList<>(len);
    for (int i = 0; i < len; i++) out.add(symbol());
    return out;
  }

  G grammar() {
    return grammar(0);
  }

  private G grammar(int depth) {
    if (depth >= 4 || range(100) < 38) {
      return leaf();
    }
    int pick = range(100);
    if (pick < 24) {
      return choice(depth);
    }
    if (pick < 40) {
      return G.seq(grammar(depth + 1), grammar(depth + 1));
    }
    if (pick < 54) {
      return G.atomic(grammar(depth + 1));
    }
    if (pick < 66) {
      return G.peek(grammar(depth + 1));
    }
    if (pick < 76) {
      return G.not(grammar(depth + 1));
    }
    if (pick < 88) {
      return G.optional(grammar(depth + 1));
    }
    if (pick < 95) {
      return G.otherwise(grammar(depth + 1), grammar(depth + 1));
    }
    return G.many(grammar(depth + 1));
  }

  private G choice(int depth) {
    int n = 2 + range(3);
    List<G> branches = new ArrayList<>(n);
    boolean sharedPrefix = range(2) == 0;
    G prefix = sharedPrefix ? G.lit(ALPHABET[range(ALPHABET.length)]) : null;
    for (int i = 0; i < n; i++) {
      G tail = grammar(depth + 1);
      branches.add(prefix == null ? tail : G.seq(prefix, tail));
    }
    switch (range(3)) {
      case 0:
        return new G.Or(branches);
      case 1:
        return G.longest(branches.toArray(new G[0]));
      default:
        return G.shortest(branches.toArray(new G[0]));
    }
  }

  private G leaf() {
    int pick = range(100);
    if (pick < 72) {
      return G.lit(ALPHABET[range(ALPHABET.length)]);
    }
    if (pick < 82) {
      return G.empty();
    }
    if (pick < 92) {
      return G.fail("f" + range(3));
    }
    return G.expect("e" + range(3));
  }

  /** Renders symbols as a character-level source string. */
  static String charSource(List<String> symbols) {
    StringBuilder sb = new StringBuilder();
    for (String s : symbols) sb.append(s);
    return sb.toString();
  }

  /** Renders symbols as a spaced, token-level source string. */
  static String tokenSource(List<String> symbols) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < symbols.size(); i++) {
      if (i > 0) sb.append(' ');
      sb.append(symbols.get(i));
    }
    return sb.toString();
  }

  /** Physical source index of token {@code tokenIndex} inside a spaced source. */
  static int tokenPosition(int tokenIndex) {
    return tokenIndex * 2;
  }

}
