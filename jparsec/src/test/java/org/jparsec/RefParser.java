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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import org.jparsec.error.ParserException;

/**
 * Reference interpreter for the backtracking and error-merging semantics of the parser
 * combinators ({@code or}, {@code longest}, {@code atomic}, {@code many}, {@code label}).
 *
 * <p>This is an independent, deliberately naive re-implementation of the state transitions
 * of {@link ParseContext} (position {@code at}, logical {@code step} and the monotonic
 * farthest-error accumulator). Property tests run generated grammars against both this
 * interpreter and the real {@link Parser} implementations and require identical outcomes:
 * success/failure, consumed length (observed through the appended EOF check), farthest
 * error position and the merged {@code expected} token set.
 *
 * <p>The interpreter only models the mergeable error kinds ({@code MISSING} and
 * {@code EXPECTING}); ranks mirror {@link ParseContext.ErrorType} ordinals.
 */
final class RefParser {

  /** Rank of {@link ParseContext.ErrorType#MISSING}. */
  static final int MISSING = 3;
  /** Rank of {@link ParseContext.ErrorType#EXPECTING}. */
  static final int EXPECTING = 4;

  /** Monotonic farthest-error accumulator; mirrors {@code ParseContext.raise()}. */
  static final class Err {
    int at = -1;
    int rank = -1;
    final LinkedHashSet<String> expected = new LinkedHashSet<String>();

    void record(int pos, int newRank, String what) {
      if (pos < at) return;
      if (pos > at) {
        at = pos;
        rank = newRank;
        expected.clear();
        expected.add(what);
        return;
      }
      if (newRank < rank) return;
      if (newRank > rank) {
        rank = newRank;
        expected.clear();
        expected.add(what);
        return;
      }
      expected.add(what);
    }
  }

  /** Result of running a model node: success flag plus the new position and logical step. */
  static final class R {
    final boolean ok;
    final int pos;
    final int step;

    private R(boolean ok, int pos, int step) {
      this.ok = ok;
      this.pos = pos;
      this.step = step;
    }

    static R ok(int pos, int step) {
      return new R(true, pos, step);
    }

    static R fail(int pos, int step) {
      return new R(false, pos, step);
    }
  }

  /** A model grammar node. Can both interpret itself and build the real jparsec parser. */
  abstract static class Node {
    abstract R run(String input, int pos, int step, Err err);
    abstract Parser<?> build();
    abstract String describe();
    @Override public final String toString() {
      return describe();
    }
  }

  static Node chr(final char c) {
    return new Node() {
      @Override R run(String input, int pos, int step, Err err) {
        if (pos < input.length() && input.charAt(pos) == c) return R.ok(pos + 1, step + 1);
        err.record(pos, MISSING, Character.toString(c));
        return R.fail(pos, step);
      }
      @Override Parser<?> build() {
        return Scanners.isChar(c);
      }
      @Override String describe() {
        return "'" + c + "'";
      }
    };
  }

  static Node empty() {
    return new Node() {
      @Override R run(String input, int pos, int step, Err err) {
        return R.ok(pos, step);
      }
      @Override Parser<?> build() {
        return Parsers.always();
      }
      @Override String describe() {
        return "empty";
      }
    };
  }

  static Node seq(final Node a, final Node b) {
    return new Node() {
      @Override R run(String input, int pos, int step, Err err) {
        R ra = a.run(input, pos, step, err);
        if (!ra.ok) return ra;
        return b.run(input, ra.pos, ra.step, err);
      }
      @Override Parser<?> build() {
        return a.build().next(b.build());
      }
      @Override String describe() {
        return "(" + a.describe() + " ~ " + b.describe() + ")";
      }
    };
  }

  static Node or(final Node a, final Node b) {
    return new Node() {
      @Override R run(String input, int pos, int step, Err err) {
        R ra = a.run(input, pos, step, err);
        if (ra.ok) return ra;
        // Position and step roll back; the recorded error survives in the shared accumulator.
        return b.run(input, pos, step, err);
      }
      @Override Parser<?> build() {
        return Parsers.or(a.build(), b.build());
      }
      @Override String describe() {
        return "(" + a.describe() + " | " + b.describe() + ")";
      }
    };
  }

  static Node longest(final Node a, final Node b) {
    return new Node() {
      @Override R run(String input, int pos, int step, Err err) {
        R ra = a.run(input, pos, step, err);
        R rb = b.run(input, pos, step, err);
        if (ra.ok && (!rb.ok || ra.pos >= rb.pos)) return ra; // tie favors the first branch
        if (rb.ok) return rb;
        return R.fail(pos, step);
      }
      @Override Parser<?> build() {
        return Parsers.longest(a.build(), b.build());
      }
      @Override String describe() {
        return "longest(" + a.describe() + ", " + b.describe() + ")";
      }
    };
  }

  static Node atomic(final Node p) {
    return new Node() {
      @Override R run(String input, int pos, int step, Err err) {
        R r = p.run(input, pos, step, err);
        if (r.ok) return R.ok(r.pos, step + 1); // collapses to a single logical step
        return R.fail(pos, step);
      }
      @Override Parser<?> build() {
        return p.build().atomic();
      }
      @Override String describe() {
        return "atomic(" + p.describe() + ")";
      }
    };
  }

  static Node many(final Node p) {
    return new Node() {
      @Override R run(String input, int pos, int step, Err err) {
        int curPos = pos;
        int curStep = step;
        for (;;) {
          R r = p.run(input, curPos, curStep, err);
          if (!r.ok) return R.ok(curPos, curStep); // roll back the failed final attempt
          if (r.pos == curPos) return R.ok(r.pos, r.step); // empty-parser guard
          curPos = r.pos;
          curStep = r.step;
        }
      }
      @Override Parser<?> build() {
        return p.build().many();
      }
      @Override String describe() {
        return "many(" + p.describe() + ")";
      }
    };
  }

  static Node optional(final Node p) {
    return or(p, empty());
  }

  static Node label(final Node p, final String name) {
    return new Node() {
      @Override R run(String input, int pos, int step, Err err) {
        R r = p.run(input, pos, step, err);
        if (r.ok) return r;
        if (r.step == step) { // no logical progress: report the label instead
          err.record(pos, EXPECTING, name);
          return R.fail(pos, step);
        }
        return r;
      }
      @Override Parser<?> build() {
        return p.build().label(name);
      }
      @Override String describe() {
        return "label(" + p.describe() + ", " + name + ")";
      }
    };
  }

  /** The expected outcome of a full parse (the parser followed by EOF, as {@code parse} does). */
  static final class Expectation {
    final boolean success;
    final int errorAt;
    final Set<String> expected;

    private Expectation(boolean success, int errorAt, Set<String> expected) {
      this.success = success;
      this.errorAt = errorAt;
      this.expected = expected;
    }

    static Expectation success() {
      return new Expectation(true, -1, null);
    }

    static Expectation failure(int errorAt, Set<String> expected) {
      return new Expectation(false, errorAt, expected);
    }

    @Override public String toString() {
      return success ? "success" : "failure@" + errorAt + " expected=" + expected;
    }
  }

  /** Runs the model for a full parse, appending the same EOF check {@code Parser.parse} uses. */
  static Expectation evaluate(Node root, String input) {
    Err err = new Err();
    R r = root.run(input, 0, 0, err);
    if (r.ok && r.pos == input.length()) return Expectation.success();
    if (r.ok) err.record(r.pos, MISSING, "EOF");
    return Expectation.failure(err.at < 0 ? 0 : err.at, err.expected);
  }

  /**
   * Asserts that running {@code root.build()} on {@code input} under {@code mode} agrees with
   * the reference interpreter. Failure messages carry the full replayable context.
   */
  static void assertConsistent(Parser.Mode mode, Node root, String input, String replayContext) {
    assertConsistent(mode, root, root.build(), input, replayContext);
  }

  /**
   * Asserts that {@code actual} agrees with the reference outcome of {@code root} on
   * {@code input}. {@code actual} may be a mutated parser, in which case this is expected
   * to throw {@link AssertionError}.
   */
  static void assertConsistent(
      Parser.Mode mode, Node root, Parser<?> actual, String input, String replayContext) {
    Expectation exp = evaluate(root, input);
    String ctx = "mode=" + mode + " grammar=" + root.describe()
        + " input=<" + input + "> reference=" + exp + " " + replayContext;
    try {
      actual.parse(input, mode);
    } catch (ParserException e) {
      if (exp.success) {
        fail(ctx + " but actual parse failed: " + e.getMessage());
      }
      assertEquals(ctx + " [error index]", exp.errorAt, e.getErrorDetails().getIndex());
      assertEquals(ctx + " [expected set]",
          new TreeSet<String>(exp.expected),
          new TreeSet<String>(e.getErrorDetails().getExpected()));
      return;
    }
    if (!exp.success) {
      fail(ctx + " but actual parse succeeded");
    }
  }
}
