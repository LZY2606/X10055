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

/**
 * A tiny, declarative grammar AST used by the backtracking / error-merging
 * property tests.
 *
 * <p>The same AST can be compiled into either a character-level parser (run on
 * the packed logical source) or a token-level parser (run on a token array
 * produced by a {@link MiniGrammarLayout}). Every successful parser returns a
 * {@code String} (the concatenation of the matched symbols, empty for epsilon)
 * so that results are directly comparable between the two engines and against
 * the reference interpreter.
 */
abstract class MiniGrammar {

  /** Logical symbols. {@code d} renders as the wide lexeme "xy" in the wide layout. */
  static final char[] SYMBOLS = {'a', 'b', 'c', 'd', 'x'};

  static boolean isSymbol(char c) {
    for (char s : SYMBOLS) if (s == c) return true;
    return false;
  }

  static final class Lit extends MiniGrammar {
    final char symbol;
    Lit(char symbol) {
      if (!isSymbol(symbol)) throw new IllegalArgumentException("bad symbol: " + symbol);
      this.symbol = symbol;
    }
  }

  static final class Seq extends MiniGrammar {
    final List<MiniGrammar> children;
    Seq(List<MiniGrammar> children) { this.children = children; }
  }

  /** Ordered choice; every failed branch rolls the input back, errors are kept. */
  static final class Or extends MiniGrammar {
    final List<MiniGrammar> children;
    Or(List<MiniGrammar> children) {
      if (children.size() < 2) throw new IllegalArgumentException("or needs >=2 branches");
      this.children = children;
    }
  }

  /** Runs every branch and keeps the longest (or shortest) successful match. */
  static final class Best extends MiniGrammar {
    final List<MiniGrammar> children;
    final boolean longest;
    Best(List<MiniGrammar> children, boolean longest) {
      if (children.size() < 2) throw new IllegalArgumentException("best needs >=2 branches");
      this.children = children;
      this.longest = longest;
    }
  }

  static final class Opt extends MiniGrammar {
    final MiniGrammar child;
    Opt(MiniGrammar child) { this.child = child; }
  }

  static final class Many extends MiniGrammar {
    final MiniGrammar child;
    Many(MiniGrammar child) { this.child = child; }
  }

  /** Backtracking wrapper: failure undoes the whole partial match. */
  static final class Atomic extends MiniGrammar {
    final MiniGrammar child;
    Atomic(MiniGrammar child) { this.child = child; }
  }

  /** Lookahead: success undoes any consumption. */
  static final class Peek extends MiniGrammar {
    final MiniGrammar child;
    Peek(MiniGrammar child) { this.child = child; }
  }

  /** Labels a branch, turning a same-position failure into "name expected". */
  static final class Label extends MiniGrammar {
    final MiniGrammar child;
    final String name;
    Label(MiniGrammar child, String name) {
      this.child = child;
      this.name = name;
    }
  }

  static final class Eps extends MiniGrammar {}

  static final class Eof extends MiniGrammar {}

  static final class Fail extends MiniGrammar {
    final String message;
    Fail(String message) { this.message = message; }
  }

  // ---- AST builders -------------------------------------------------------

  static MiniGrammar lit(char c) { return new Lit(c); }
  static MiniGrammar eps() { return new Eps(); }
  static MiniGrammar eof() { return new Eof(); }
  static MiniGrammar fail(String message) { return new Fail(message); }
  static MiniGrammar seq(MiniGrammar... children) { return new Seq(Arrays.asList(children)); }
  static MiniGrammar or(MiniGrammar... children) { return new Or(Arrays.asList(children)); }
  static MiniGrammar longest(MiniGrammar... children) {
    return new Best(Arrays.asList(children), true);
  }
  static MiniGrammar shortest(MiniGrammar... children) {
    return new Best(Arrays.asList(children), false);
  }
  static MiniGrammar opt(MiniGrammar child) { return new Opt(child); }
  static MiniGrammar many(MiniGrammar child) { return new Many(child); }
  static MiniGrammar atomic(MiniGrammar child) { return new Atomic(child); }
  static MiniGrammar peek(MiniGrammar child) { return new Peek(child); }
  static MiniGrammar label(MiniGrammar child, String name) { return new Label(child, name); }

  /** Grammar followed by EOF, so that both engines must consume all input. */
  static MiniGrammar fully(MiniGrammar grammar) {
    return seq(grammar, eof());
  }

  // ---- compilation --------------------------------------------------------

  /** Compiles to a character-level parser running on packed logical source. */
  @SuppressWarnings("unchecked")
  static Parser<String> compileChar(MiniGrammar g) {
    if (g instanceof Lit) {
      char c = ((Lit) g).symbol;
      return Scanners.isChar(CharPredicates.isChar(c)).retn(String.valueOf(c));
    }
    if (g instanceof Eps) return Parsers.constant("");
    if (g instanceof Eof) return (Parser<String>) (Parser<?>) Parsers.EOF.retn("");
    if (g instanceof Fail) return Parsers.fail(((Fail) g).message);
    if (g instanceof Seq) return seqChar(((Seq) g).children);
    if (g instanceof Or) return Parsers.or(compileCharList(((Or) g).children));
    if (g instanceof Best) {
      Best best = (Best) g;
      Parser<String>[] ps = compileCharList(best.children);
      return best.longest ? Parsers.longest(ps) : Parsers.shortest(ps);
    }
    if (g instanceof Opt) return Parsers.or(compileChar(((Opt) g).child), Parsers.constant(""));
    if (g instanceof Many) {
      return compileChar(((Many) g).child).many().map(values -> String.join("", values));
    }
    if (g instanceof Atomic) return compileChar(((Atomic) g).child).atomic();
    if (g instanceof Peek) return compileChar(((Peek) g).child).peek();
    if (g instanceof Label) {
      Label l = (Label) g;
      return compileChar(l.child).label(l.name);
    }
    throw new AssertionError("unknown node: " + g.getClass());
  }

  /** Compiles to a token-level parser matching token values equal to symbols. */
  @SuppressWarnings("unchecked")
  static Parser<String> compileToken(MiniGrammar g) {
    if (g instanceof Lit) {
      final String symbol = String.valueOf(((Lit) g).symbol);
      return Parsers.token(new TokenMap<String>() {
        @Override public String map(Token token) {
          return symbol.equals(String.valueOf(token.value())) ? symbol : null;
        }
        @Override public String toString() { return symbol; }
      });
    }
    if (g instanceof Eps) return Parsers.constant("");
    if (g instanceof Eof) return (Parser<String>) (Parser<?>) Parsers.EOF.retn("");
    if (g instanceof Fail) return Parsers.fail(((Fail) g).message);
    if (g instanceof Seq) return seqToken(((Seq) g).children);
    if (g instanceof Or) return Parsers.or(compileTokenList(((Or) g).children));
    if (g instanceof Best) {
      Best best = (Best) g;
      Parser<String>[] ps = compileTokenList(best.children);
      return best.longest ? Parsers.longest(ps) : Parsers.shortest(ps);
    }
    if (g instanceof Opt) return Parsers.or(compileToken(((Opt) g).child), Parsers.constant(""));
    if (g instanceof Many) {
      return compileToken(((Many) g).child).many().map(values -> String.join("", values));
    }
    if (g instanceof Atomic) return compileToken(((Atomic) g).child).atomic();
    if (g instanceof Peek) return compileToken(((Peek) g).child).peek();
    if (g instanceof Label) {
      Label l = (Label) g;
      return compileToken(l.child).label(l.name);
    }
    throw new AssertionError("unknown node: " + g.getClass());
  }

  private static Parser<String> seqChar(List<MiniGrammar> nodes) {
    Parser<String> acc = Parsers.constant("");
    for (MiniGrammar node : nodes) {
      final Parser<String> right = compileChar(node);
      acc = acc.next(left -> right.map(rightValue -> left + rightValue));
    }
    return acc;
  }

  private static Parser<String> seqToken(List<MiniGrammar> nodes) {
    Parser<String> acc = Parsers.constant("");
    for (MiniGrammar node : nodes) {
      final Parser<String> right = compileToken(node);
      acc = acc.next(left -> right.map(rightValue -> left + rightValue));
    }
    return acc;
  }

  private static Parser<String>[] compileCharList(List<MiniGrammar> nodes) {
    List<Parser<String>> parsers = new ArrayList<>();
    for (MiniGrammar node : nodes) parsers.add(compileChar(node));
    return parsers.toArray(new Parser[0]);
  }

  private static Parser<String>[] compileTokenList(List<MiniGrammar> nodes) {
    List<Parser<String>> parsers = new ArrayList<>();
    for (MiniGrammar node : nodes) parsers.add(compileToken(node));
    return parsers.toArray(new Parser[0]);
  }

  private MiniGrammar() {}
}
