package org.jparsec;

import java.util.ArrayList;
import java.util.List;

/**
 * Independent reference interpreter for {@link MiniGrammar}. It re-implements the
 * observable state boundary of jparsec's parser combinators from scratch:
 *
 * <ul>
 *   <li>{@code or} restores the input position (but NOT the error state) between
 *       branches and picks the first succeeding branch.</li>
 *   <li>{@code longest} runs every branch from the same position and picks the one
 *       consuming the most input; ties favor the earliest branch.</li>
 *   <li>{@code atomic} rolls the position back on failure but keeps the recorded
 *       error; {@code peek} rolls the position back on success.</li>
 *   <li>errors are merged by "furthest position wins"; at the same position a
 *       higher-priority error kind replaces, and equal-priority expected tokens
 *       are unioned in recording order.</li>
 *   <li>{@code many} stops when the body fails (restoring its position) or when the
 *       body succeeds without consuming input (the empty-parser guard).</li>
 * </ul>
 *
 * <p>The result is an {@link Evidence} value that can be replayed and diffed against
 * the evidence captured from a real jparsec run.
 */
final class MiniReference {

  /** Mirrors the relative ordering of {@code ParseContext.ErrorType} ordinals. */
  private static final int MISSING = 3;
  private static final int EXPECTING = 4;

  /** The observable outcome of a parse: success evidence or failure evidence. */
  static final class Evidence {
    final boolean ok;
    /** Success: the path-encoding value. */
    final String value;
    /** Success: how many characters were consumed. */
    final int end;
    /** Failure: 0-based character index of the most relevant error. */
    final int errorIndex;
    /** Failure: expected tokens in recording order (may contain duplicates). */
    final List<String> expected;
    /** Failure: what was encountered at the error position. */
    final String encountered;

    private Evidence(
        boolean ok, String value, int end, int errorIndex, List<String> expected,
        String encountered) {
      this.ok = ok;
      this.value = value;
      this.end = end;
      this.errorIndex = errorIndex;
      this.expected = expected;
      this.encountered = encountered;
    }

    static Evidence success(String value, int end) {
      return new Evidence(true, value, end, -1, null, null);
    }

    static Evidence failure(int errorIndex, List<String> expected, String encountered) {
      return new Evidence(false, null, -1, errorIndex, expected, encountered);
    }

    @Override public String toString() {
      if (ok) {
        return "ok(value=" + value + ", end=" + end + ")";
      }
      return "fail(index=" + errorIndex + ", expected=" + expected
          + ", encountered=" + encountered + ")";
    }
  }

  /** Runs {@code grammar} against {@code input} and returns the observable evidence. */
  static Evidence run(MiniGrammar grammar, String input) {
    MiniReference ref = new MiniReference(input);
    String value = ref.eval(grammar);
    if (value != null) {
      return Evidence.success(value, ref.pos);
    }
    String encountered = ref.errorPos < input.length()
        ? Character.toString(input.charAt(ref.errorPos)) : "EOF";
    return Evidence.failure(ref.errorPos, new ArrayList<String>(ref.expected), encountered);
  }

  private final String input;
  private int pos;
  private int errorPos = -1;
  private int errorRank = -1;
  private final List<String> expected = new ArrayList<String>();

  private MiniReference(String input) {
    this.input = input;
  }

  /** Mirrors {@code ParseContext.raise}: furthest position wins, then priority, then merge. */
  private void raise(int rank, String what) {
    if (pos < errorPos) return;
    if (pos > errorPos) {
      errorPos = pos;
      errorRank = rank;
      expected.clear();
      expected.add(what);
      return;
    }
    if (rank < errorRank) return;
    if (rank > errorRank) {
      errorRank = rank;
      expected.clear();
      expected.add(what);
      return;
    }
    expected.add(what);
  }

  /** Returns the path-encoding value on success, or {@code null} on failure. */
  private String eval(MiniGrammar node) {
    if (node instanceof MiniGrammar.Lit) {
      char c = ((MiniGrammar.Lit) node).c;
      if (pos < input.length() && input.charAt(pos) == c) {
        pos++;
        return Character.toString(c);
      }
      raise(MISSING, Character.toString(c));
      return null;
    }
    if (node instanceof MiniGrammar.Expect) {
      raise(EXPECTING, ((MiniGrammar.Expect) node).name);
      return null;
    }
    if (node instanceof MiniGrammar.Seq) {
      MiniGrammar.Seq seq = (MiniGrammar.Seq) node;
      String left = eval(seq.left);
      if (left == null) return null;
      String right = eval(seq.right);
      if (right == null) return null;
      return "(" + left + " " + right + ")";
    }
    if (node instanceof MiniGrammar.Or) {
      MiniGrammar.Or or = (MiniGrammar.Or) node;
      int saved = pos;
      for (int i = 0; i < or.branches.length; i++) {
        String value = eval(or.branches[i]);
        if (value != null) return "or" + i + ":" + value;
        // Position is rolled back; the error recorded by the dead branch survives.
        pos = saved;
      }
      return null;
    }
    if (node instanceof MiniGrammar.Longest) {
      MiniGrammar.Longest longest = (MiniGrammar.Longest) node;
      int saved = pos;
      String best = null;
      int bestEnd = -1;
      int bestIndex = -1;
      for (int i = 0; i < longest.branches.length; i++) {
        pos = saved;
        String value = eval(longest.branches[i]);
        if (value != null && pos > bestEnd) {
          best = value;
          bestEnd = pos;
          bestIndex = i;
        }
      }
      if (best == null) {
        pos = saved;
        return null;
      }
      pos = bestEnd;
      return "long" + bestIndex + ":" + best;
    }
    if (node instanceof MiniGrammar.Atomic) {
      int saved = pos;
      String value = eval(((MiniGrammar.Atomic) node).body);
      if (value == null) {
        pos = saved;
        return null;
      }
      return "atm(" + value + ")";
    }
    if (node instanceof MiniGrammar.Peek) {
      int saved = pos;
      String value = eval(((MiniGrammar.Peek) node).body);
      if (value == null) return null;
      pos = saved;
      return "peek(" + value + ")";
    }
    if (node instanceof MiniGrammar.Many) {
      List<String> values = new ArrayList<String>();
      for (;;) {
        int saved = pos;
        String value = eval(((MiniGrammar.Many) node).body);
        if (value == null) {
          pos = saved;
          break;
        }
        if (pos == saved) break; // empty-parser guard: no progress, stop.
        values.add(value);
      }
      return values.toString();
    }
    throw new IllegalArgumentException("unknown node: " + node.getClass());
  }
}
