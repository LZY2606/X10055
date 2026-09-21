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
import java.util.Collections;
import java.util.List;

/**
 * Reference interpreter for {@link MiniGrammar}.
 *
 * <p>An independent, deliberately small re-implementation of the exact
 * backtracking / error-merging state machine embedded in jparsec's package
 * private {@code ParseContext}, {@code Parser#atomic()},
 * {@code Parser#peek()}, {@code Parser#label()} and {@code BestParser}.
 * Positions are logical (packed); callers translate them through a
 * {@link MiniGrammarLayout} before comparing with the token-level engine.
 *
 * <p>Error precedence mirrors {@code ParseContext.ErrorType}: bare literal
 * misses are {@code MISSING} and merge with other mergeable errors at the same
 * position; a labeled failure is {@code EXPECTING} and overrides
 * {@code MISSING}; {@code FAILURE} (fail()) is non-mergeable and wins over
 * earlier errors at the same position. A failure raised behind the current
 * furthest error position is discarded.
 */
final class MiniReference {

  private static final int NONE = 0, MISSING = 1, EXPECTING = 2, FAILURE = 3;

  /** Mutable parse state plus the furthest-error accumulator. */
  static final class State {
    final String input;
    int at;
    int step;
    String result = "";

    int errorAt;
    int errorType = NONE;
    final List<String> errors = new ArrayList<>();

    State(String input) {
      this.input = input;
      this.errorAt = 0;
    }

    boolean eof() { return at >= input.length(); }

    String encountered() {
      return at >= input.length() ? "EOF" : String.valueOf(input.charAt(at));
    }

    /** Mirrors ParseContext.raise, including the same-position merge rule. */
    void raise(int type, String subject) {
      if (at < errorAt) return;
      if (at > errorAt) {
        errorAt = at;
        errorType = type;
        errors.clear();
        errors.add(subject);
        return;
      }
      if (type < errorType) return;
      if (type > errorType) {
        errorType = type;
        errors.clear();
        errors.add(subject);
        return;
      }
      if (type == MISSING || type == EXPECTING) {
        errors.add(subject);
      }
    }

    void snapshot(Snapshot snapshot) {
      snapshot.at = at;
      snapshot.step = step;
      snapshot.result = result;
    }

    void restore(Snapshot snapshot) {
      at = snapshot.at;
      step = snapshot.step;
      result = snapshot.result;
    }
  }

  private static final class Snapshot {
    int at;
    int step;
    String result;
  }

  private MiniReference() {}

  /** Interprets the grammar and projects the final state into evidence. */
  static ParseEvidence run(MiniGrammar grammar, String packedInput) {
    State state = new State(packedInput);
    boolean ok = apply(grammar, state);
    if (ok) {
      return ParseEvidence.success(state.result);
    }
    List<String> expected;
    String unexpected = null;
    String failureMessage = null;
    if (state.errorType == FAILURE) {
      expected = Collections.emptyList();
      failureMessage = state.errors.isEmpty() ? null : state.errors.get(0);
    } else {
      expected = dedupInOrder(state.errors);
    }
    String encountered;
    int errorPos = Math.min(state.errorAt, packedInput.length());
    encountered = errorPos >= packedInput.length()
        ? "EOF"
        : String.valueOf(packedInput.charAt(errorPos));
    return ParseEvidence.failure(errorPos, expected, unexpected, failureMessage, encountered);
  }

  private static List<String> dedupInOrder(List<String> raw) {
    List<String> out = new ArrayList<>();
    for (String s : raw) {
      if (!out.contains(s)) out.add(s);
    }
    return out;
  }

  private static boolean apply(MiniGrammar g, final State ctxt) {
    if (g instanceof MiniGrammar.Lit) {
      char symbol = ((MiniGrammar.Lit) g).symbol;
      String name = String.valueOf(symbol);
      if (ctxt.eof() || ctxt.input.charAt(ctxt.at) != symbol) {
        ctxt.raise(MISSING, name);
        return false;
      }
      ctxt.at++;
      ctxt.step++;
      ctxt.result = name;
      return true;
    }
    if (g instanceof MiniGrammar.Eps) {
      ctxt.result = "";
      return true;
    }
    if (g instanceof MiniGrammar.Eof) {
      if (!ctxt.eof()) {
        ctxt.raise(MISSING, ParseContext.EOF);
        return false;
      }
      ctxt.result = "";
      return true;
    }
    if (g instanceof MiniGrammar.Fail) {
      ctxt.raise(FAILURE, ((MiniGrammar.Fail) g).message);
      return false;
    }
    if (g instanceof MiniGrammar.Seq) {
      for (MiniGrammar child : ((MiniGrammar.Seq) g).children) {
        if (!apply(child, ctxt)) return false;
      }
      return true;
    }
    if (g instanceof MiniGrammar.Or) {
      Snapshot snapshot = new Snapshot();
      ctxt.snapshot(snapshot);
      for (MiniGrammar child : ((MiniGrammar.Or) g).children) {
        if (apply(child, ctxt)) return true;
        // Partial match does not prevent fallback: restore, errors survive.
        ctxt.restore(snapshot);
      }
      return false;
    }
    if (g instanceof MiniGrammar.Best) {
      return applyBest((MiniGrammar.Best) g, ctxt);
    }
    if (g instanceof MiniGrammar.Opt) {
      Snapshot snapshot = new Snapshot();
      ctxt.snapshot(snapshot);
      if (apply(((MiniGrammar.Opt) g).child, ctxt)) return true;
      ctxt.restore(snapshot);
      ctxt.result = "";
      return true;
    }
    if (g instanceof MiniGrammar.Many) {
      MiniGrammar child = ((MiniGrammar.Many) g).child;
      StringBuilder matched = new StringBuilder();
      int physical = ctxt.at;
      for (;;) {
        int logical = ctxt.step;
        if (!apply(child, ctxt)) {
          ctxt.step = logical;
          ctxt.at = physical;
          ctxt.result = matched.toString();
          return true;
        }
        matched.append(ctxt.result);
        if (physical == ctxt.at) {
          // Empty-parser guard: stop on a zero-width success.
          ctxt.result = matched.toString();
          return true;
        }
        physical = ctxt.at;
      }
    }
    if (g instanceof MiniGrammar.Atomic) {
      int at = ctxt.at;
      int step = ctxt.step;
      if (apply(((MiniGrammar.Atomic) g).child, ctxt)) {
        ctxt.step = step + 1;
        return true;
      }
      ctxt.at = at;
      ctxt.step = step;
      return false;
    }
    if (g instanceof MiniGrammar.Peek) {
      int at = ctxt.at;
      int step = ctxt.step;
      boolean ok = apply(((MiniGrammar.Peek) g).child, ctxt);
      if (ok) {
        ctxt.at = at;
        ctxt.step = step;
      }
      return ok;
    }
    if (g instanceof MiniGrammar.Label) {
      MiniGrammar.Label label = (MiniGrammar.Label) g;
      int physical = ctxt.at;
      int logical = ctxt.step;
      if (apply(label.child, ctxt)) return true;
      if (ctxt.step == logical) {
        ctxt.at = physical;
        ctxt.raise(EXPECTING, label.name);
      }
      return false;
    }
    throw new AssertionError("unknown node: " + g.getClass());
  }

  /** Mirrors BestParser: run every branch, select by length, ties favor earlier. */
  private static boolean applyBest(MiniGrammar.Best best, State ctxt) {
    Snapshot original = new Snapshot();
    ctxt.snapshot(original);
    boolean any = false;
    int bestAt = 0;
    int bestStep = 0;
    String bestResult = null;
    for (MiniGrammar child : best.children) {
      ctxt.restore(original);
      if (!apply(child, ctxt)) continue;
      if (!any) {
        any = true;
        bestAt = ctxt.at;
        bestStep = ctxt.step;
        bestResult = ctxt.result;
      } else if (best.longest ? ctxt.at > bestAt : ctxt.at < bestAt) {
        bestAt = ctxt.at;
        bestStep = ctxt.step;
        bestResult = ctxt.result;
      }
    }
    if (!any) return false;
    ctxt.at = bestAt;
    ctxt.step = bestStep;
    ctxt.result = bestResult;
    return true;
  }
}
