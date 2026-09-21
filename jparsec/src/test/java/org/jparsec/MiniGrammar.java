package org.jparsec;

import java.util.ArrayList;
import java.util.List;

/**
 * A tiny grammar DSL used by {@link BacktrackingEvidenceTest} to exercise parser
 * combinator backtracking and error merging.
 *
 * <p>Each node can be compiled to a jparsec {@code Parser<String>} whose return value
 * encodes the selection path taken (which {@code or}/{@code longest} branch matched),
 * so that "which alternative was chosen" is observable evidence, not just the final
 * return value. The same node is also interpreted by {@link MiniReference}, an
 * independent reference implementation of jparsec's backtracking/error semantics.
 */
abstract class MiniGrammar {

  /** Compiles this node to a jparsec parser with path-encoding return values. */
  abstract Parser<String> toParser();

  static MiniGrammar lit(char c) {
    return new Lit(c);
  }

  static MiniGrammar expect(String name) {
    return new Expect(name);
  }

  static MiniGrammar seq(MiniGrammar left, MiniGrammar right) {
    return new Seq(left, right);
  }

  static MiniGrammar or(MiniGrammar... branches) {
    return new Or(branches);
  }

  static MiniGrammar longest(MiniGrammar... branches) {
    return new Longest(branches);
  }

  static MiniGrammar atomic(MiniGrammar body) {
    return new Atomic(body);
  }

  static MiniGrammar peek(MiniGrammar body) {
    return new Peek(body);
  }

  static MiniGrammar many(MiniGrammar body) {
    return new Many(body);
  }

  /** Matches a single character; on failure reports the character as missing. */
  static final class Lit extends MiniGrammar {
    final char c;

    Lit(char c) {
      this.c = c;
    }

    @Override Parser<String> toParser() {
      return Scanners.isChar(c).retn(Character.toString(c));
    }

    @Override public String toString() {
      return "'" + c + "'";
    }
  }

  /** Always fails, reporting {@code name} as logically expected. */
  static final class Expect extends MiniGrammar {
    final String name;

    Expect(String name) {
      this.name = name;
    }

    @Override Parser<String> toParser() {
      return Parsers.expect(name);
    }

    @Override public String toString() {
      return "expect(" + name + ")";
    }
  }

  static final class Seq extends MiniGrammar {
    final MiniGrammar left;
    final MiniGrammar right;

    Seq(MiniGrammar left, MiniGrammar right) {
      this.left = left;
      this.right = right;
    }

    @Override Parser<String> toParser() {
      return Parsers.sequence(
          left.toParser(), right.toParser(), (a, b) -> "(" + a + " " + b + ")");
    }

    @Override public String toString() {
      return "seq(" + left + ", " + right + ")";
    }
  }

  static final class Or extends MiniGrammar {
    final MiniGrammar[] branches;

    Or(MiniGrammar... branches) {
      this.branches = branches;
    }

    @Override Parser<String> toParser() {
      List<Parser<String>> parsers = new ArrayList<Parser<String>>();
      for (int i = 0; i < branches.length; i++) {
        final int index = i;
        parsers.add(branches[i].toParser().map(v -> "or" + index + ":" + v));
      }
      return Parsers.or(parsers);
    }

    @Override public String toString() {
      return join("or", branches);
    }
  }

  static final class Longest extends MiniGrammar {
    final MiniGrammar[] branches;

    Longest(MiniGrammar... branches) {
      this.branches = branches;
    }

    @Override Parser<String> toParser() {
      List<Parser<String>> parsers = new ArrayList<Parser<String>>();
      for (int i = 0; i < branches.length; i++) {
        final int index = i;
        parsers.add(branches[i].toParser().map(v -> "long" + index + ":" + v));
      }
      return Parsers.longest(parsers);
    }

    @Override public String toString() {
      return join("longest", branches);
    }
  }

  static final class Atomic extends MiniGrammar {
    final MiniGrammar body;

    Atomic(MiniGrammar body) {
      this.body = body;
    }

    @Override Parser<String> toParser() {
      return body.toParser().atomic().map(v -> "atm(" + v + ")");
    }

    @Override public String toString() {
      return "atomic(" + body + ")";
    }
  }

  static final class Peek extends MiniGrammar {
    final MiniGrammar body;

    Peek(MiniGrammar body) {
      this.body = body;
    }

    @Override Parser<String> toParser() {
      return body.toParser().peek().map(v -> "peek(" + v + ")");
    }

    @Override public String toString() {
      return "peek(" + body + ")";
    }
  }

  static final class Many extends MiniGrammar {
    final MiniGrammar body;

    Many(MiniGrammar body) {
      this.body = body;
    }

    @Override Parser<String> toParser() {
      return body.toParser().many().map(List::toString);
    }

    @Override public String toString() {
      return "many(" + body + ")";
    }
  }

  private static String join(String name, MiniGrammar[] branches) {
    StringBuilder sb = new StringBuilder(name).append('(');
    for (int i = 0; i < branches.length; i++) {
      if (i > 0) sb.append(", ");
      sb.append(branches[i]);
    }
    return sb.append(')').toString();
  }
}
