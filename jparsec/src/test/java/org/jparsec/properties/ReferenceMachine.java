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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Independent reference interpreter for {@link G} grammars.
 *
 * <p>It mirrors the state boundaries implemented by {@code ParseContext}/{@code BestParser}/
 * the combinators in {@code Parser}, but is written from scratch:
 * <ul>
 * <li>{@code at} is the physical cursor into the symbol list, {@code step} the logical
 *     step counter used for zero-width-loop detection and for {@code atomic}/{@code label}
 *     rollback semantics.
 * <li>Exactly one "farthest error" record is kept ({@code errorAt}, precedence
 *     {@code errorType}, merged {@code messages}). Raising at a nearer cursor is ignored;
 *     a higher precedence at the same cursor replaces; the same mergeable type at the same
 *     cursor is unioned. Backtracking never rewinds the error record.
 * </ul>
 * This is the oracle every property is checked against.
 */
final class ReferenceMachine {

  // Error precedence mirrors ParseContext.ErrorType ordinal ordering:
  // NONE < DELIMITING < UNEXPECTED < MISSING < EXPECTING < FAILURE.
  private static final int TYPE_UNEXPECTED = 0;
  private static final int TYPE_MISSING = 1;
  private static final int TYPE_EXPECTING = 2;
  private static final int TYPE_FAILURE = 3;

  private final List<String> input;
  private final boolean tokenLevel;

  private int at;
  private int step;

  private int errorAt;
  private int errorIndexPhysical;
  private int errorType = -1;
  private final List<String> messages = new ArrayList<>();
  private int suppressionDepth = 0;

  private ReferenceMachine(List<String> input, boolean tokenLevel) {
    this.input = input;
    this.errorAt = 0;
    this.tokenLevel = tokenLevel;
  }

  /**
   * Runs at character level: one symbol is one source character, so logical and
   * physical positions coincide.
   */
  static Outcome run(G grammar, List<String> symbols) {
    return run(grammar, symbols, false);
  }

  /**
   * Runs at token level: one symbol is one token whose physical start is
   * {@code 2 * slot} in a space-separated source. The distinction matters because
   * production keeps two coordinates — a logical token cursor ({@code at}) and a
   * physical source index ({@code errorIndex}) — and {@code otherwise} compares
   * the physical error index against the logical cursor.
   */
  static Outcome runToken(G grammar, List<String> symbols) {
    return run(grammar, symbols, true);
  }

  private static Outcome run(G grammar, List<String> symbols, boolean tokenLevel) {
    ReferenceMachine m = new ReferenceMachine(symbols, tokenLevel);
    boolean ok = m.eval(grammar);
    if (ok) {
      // Mirrors parser.followedBy(Parsers.EOF): the EOF terminal raises a
      // mergeable error at the current cursor, which still loses to any farther
      // error recorded along the (successful) parse.
      if (m.at < symbols.size()) {
        m.raise(TYPE_MISSING, "EOF");
        return m.toFailure(tokenLevel);
      }
      return Outcome.succeeded(m.physical(m.at));
    }
    return m.toFailure(tokenLevel);
  }

  private Outcome toFailure(boolean physicalCoordinates) {
    // The rendered ParserException reports toIndex(currentErrorAt): the token
    // START at token level, the character position at char level. The separate
    // errorIndexPhysical only feeds the otherwise guard, not the diagnostic.
    int position = physicalCoordinates ? physical(errorAt) : errorAt;
    if (errorType == TYPE_FAILURE) {
      return Outcome.failure(position,
          messages.isEmpty() ? null : messages.get(0));
    }
    if (errorType == TYPE_UNEXPECTED) {
      return Outcome.unexpected(position,
          messages.isEmpty() ? null : messages.get(0));
    }
    Set<String> expected = new LinkedHashSet<>(messages);
    return Outcome.expecting(position, expected);
  }

  /** Physical source index of logical cursor {@code logicalAt}. */
  private int physical(int logicalAt) {
    if (!tokenLevel) return logicalAt;
    if (logicalAt >= input.size()) return Math.max(0, input.size() * 2 - 1);
    return logicalAt * 2;
  }

  private boolean eval(G g) {
    if (g instanceof G.Lit) {
      String want = ((G.Lit) g).name;
      if (at < input.size() && input.get(at).equals(want)) {
        at++;
        step++;
        return true;
      }
      raise(TYPE_MISSING, want);
      return false;
    }
    if (g instanceof G.Empty) {
      return true;
    }
    if (g instanceof G.Fail) {
      raise(TYPE_FAILURE, ((G.Fail) g).name);
      return false;
    }
    if (g instanceof G.Expect) {
      raise(TYPE_EXPECTING, ((G.Expect) g).name);
      return false;
    }
    if (g instanceof G.Seq) {
      for (G child : ((G.Seq) g).children) {
        if (!eval(child)) return false;
      }
      return true;
    }
    if (g instanceof G.Or) {
      return runOr(((G.Or) g).children);
    }
    if (g instanceof G.Best) {
      return runBest((G.Best) g);
    }
    if (g instanceof G.Atomic) {
      int savedAt = at;
      int savedStep = step;
      if (eval(((G.Atomic) g).child)) {
        step = savedStep + 1;
        return true;
      }
      at = savedAt;
      step = savedStep;
      return false;
    }
    if (g instanceof G.Peek) {
      int savedAt = at;
      int savedStep = step;
      boolean ok = eval(((G.Peek) g).child);
      if (ok) {
        at = savedAt;
        step = savedStep;
      }
      return ok;
    }
    if (g instanceof G.Not) {
      int savedAt = at;
      int savedStep = step;
      // not() is peek().ifelse(unexpected, always). ifelse runs the condition
      // via withErrorSuppressed, so raises inside are dropped and the record
      // is frozen for the duration (nested otherwise/best guards observe the
      // frozen state). On success peek restores the cursor and unexpected is
      // raised; on failure ifelse restores the cursor and always() succeeds.
      boolean childSucceeded = suppressedWhile(() -> eval(((G.Not) g).child));
      at = savedAt;
      step = savedStep;
      if (childSucceeded) {
        raise(TYPE_UNEXPECTED, nameOf(((G.Not) g).child));
        return false;
      }
      return true;
    }
    if (g instanceof G.Optional) {
      int savedAt = at;
      int savedStep = step;
      if (eval(((G.Optional) g).child)) return true;
      at = savedAt;
      step = savedStep;
      return true;
    }
    if (g instanceof G.Otherwise) {
      int savedAt = at;
      int savedStep = step;
      if (eval(((G.Otherwise) g).primary)) return true;
      // Mirrors Parser.otherwise, which compares the PHYSICAL error index with
      // the logical cursor. The two only differ at token level.
      if (errorIndexPhysical > physical(savedAt)) return false;
      at = savedAt;
      step = savedStep;
      return eval(((G.Otherwise) g).fallback);
    }
    if (g instanceof G.Many) {
      G child = ((G.Many) g).child;
      int physical = at;
      int logical = step;
      while (true) {
        if (!eval(child)) {
          at = physical;
          step = logical;
          return true;
        }
        if (at == physical) return true;
        physical = at;
        logical = step;
      }
    }
    throw new AssertionError("unknown node " + g.getClass());
  }

  private boolean runOr(List<G> branches) {
    int savedAt = at;
    int savedStep = step;
    for (G branch : branches) {
      if (eval(branch)) return true;
      at = savedAt;
      step = savedStep;
    }
    return false;
  }

  private boolean runBest(G.Best best) {
    int savedAt = at;
    int savedStep = step;
    int chosenAt = -1;
    int chosenStep = -1;
    boolean any = false;
    for (G branch : best.children) {
      at = savedAt;
      step = savedStep;
      if (!eval(branch)) continue;
      if (!any) {
        any = true;
        chosenAt = at;
        chosenStep = step;
      } else if (best.longest ? at > chosenAt : at < chosenAt) {
        chosenAt = at;
        chosenStep = step;
      }
    }
    if (!any) {
      at = savedAt;
      step = savedStep;
      return false;
    }
    at = chosenAt;
    step = chosenStep;
    return true;
  }

  private void raise(int type, String subject) {
    // ParseContext.raise returns immediately when error recording is suppressed.
    // Nested combinators still READ the (frozen) error record, e.g. otherwise's
    // partial-match guard.
    if (suppressionDepth > 0) return;
    if (at < errorAt) return;
    if (at > errorAt) {
      errorAt = at;
      // ParseContext.setErrorState stores getIndex(): the token END for a
      // mismatching terminal (at < end), the token start at EOF. Char level
      // maps both to the same physical position.
      errorIndexPhysical = raisedPhysicalIndex(at);
      errorType = type;
      messages.clear();
      messages.add(subject);
      return;
    }
    if (errorType < 0) {
      errorType = type;
      errorIndexPhysical = physical(at);
      messages.add(subject);
      return;
    }
    if (type > errorType) {
      errorType = type;
      // Higher precedence at the SAME logical cursor goes through
      // setErrorState(at, getIndex(), ...), where getIndex() maps the logical
      // slot back to the token START (unlike the farther-error branch above).
      errorIndexPhysical = physical(at);
      messages.clear();
      messages.add(subject);
    } else if (type == errorType && (type == TYPE_MISSING || type == TYPE_EXPECTING)) {
      messages.add(subject);
    }
  }

  /**
   * Physical index recorded when an error is raised at a strictly farther logical
   * position. A failing terminal reports the position just past the offending token.
   */
  private int raisedPhysicalIndex(int logicalAt) {
    if (!tokenLevel) return logicalAt;
    if (logicalAt >= input.size()) return physical(logicalAt);
    // Symbols are single characters: past this token == token start + 1.
    return logicalAt * 2 + 1;
  }

  /** Name reported when {@code not} turns a success into an unexpected error. */
  private static String nameOf(G g) {
    if (g instanceof G.Lit) return ((G.Lit) g).name;
    return G.show(g);
  }

  private interface Action { boolean run(); }

  /**
   * Runs {@code action} with error recording suppressed, mirroring
   * {@code ParseContext.withErrorSuppressed}. Unlike a snapshot/restore, the
   * record is simply frozen: raises are dropped and nested readers see the
   * pre-existing record.
   */
  private boolean suppressedWhile(Action action) {
    suppressionDepth++;
    try {
      return action.run();
    } finally {
      suppressionDepth--;
    }
  }

}
