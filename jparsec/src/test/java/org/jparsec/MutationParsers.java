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

/**
 * Deliberately broken parsers used only by the backtracking property tests to prove the new
 * cases can fail when a single semantic boundary is removed. Each class deletes exactly one
 * mechanism that the production combinators implement:
 * <ol>
 * <li>{@link #noRollbackOr} does not restore the input cursor between failed alternatives.
 * <li>{@link #noMergeOr} tries alternatives with error recording suppressed for all but the
 *     last, so same-position expected tokens are never merged.
 * <li>{@link #noEmptyGuardOtherwise} falls back even after a partial match, which collapses
 *     {@code otherwise} into plain {@code or}.
 * </ol>
 * The production code is untouched; these live under {@code src/test}.
 */
public final class MutationParsers {

  /** Ordered choice without position rollback between alternatives. */
  public static <T> Parser<T> noRollbackOr(Parser<? extends T>... alternatives) {
    return new Parser<T>() {
      @Override boolean apply(ParseContext ctxt) {
        final Object result = ctxt.result;
        for (Parser<? extends T> p : alternatives) {
          if (p.apply(ctxt)) return true;
          // MUTANT: production restores (step, at, result) here. We deliberately don't,
          // so a branch that consumed a shared prefix poisons the starting position.
          ctxt.result = result;
        }
        return false;
      }
      @Override public String toString() {
        return "noRollbackOr";
      }
    };
  }

  /** Ordered choice whose alternatives do not contribute merged same-position errors. */
  public static <T> Parser<T> noMergeOr(Parser<? extends T>... alternatives) {
    return new Parser<T>() {
      @Override boolean apply(ParseContext ctxt) {
        final Object result = ctxt.result;
        final int at = ctxt.at;
        final int step = ctxt.step;
        for (int i = 0; i < alternatives.length; i++) {
          Parser<? extends T> p = alternatives[i];
          // MUTANT: production lets every branch record into the shared farthest-error
          // record. Suppress all but the last, so only its expected token survives.
          boolean ok = i < alternatives.length - 1
              ? ctxt.withErrorSuppressed(p)
              : p.apply(ctxt);
          if (ok) return true;
          ctxt.set(step, at, result);
        }
        return false;
      }
      @Override public String toString() {
        return "noMergeOr";
      }
    };
  }

  /** {@code otherwise} without the "no partial match" guard. */
  public static <T> Parser<T> noEmptyGuardOtherwise(
      Parser<? extends T> primary, Parser<? extends T> fallback) {
    return new Parser<T>() {
      @Override boolean apply(ParseContext ctxt) {
        final Object result = ctxt.result;
        final int at = ctxt.at;
        final int step = ctxt.step;
        if (primary.apply(ctxt)) return true;
        // MUTANT: production only falls back when ctxt.errorIndex() == at (zero-width
        // failure). Here we always fall back, ignoring a partial match.
        ctxt.set(step, at, result);
        return fallback.apply(ctxt);
      }
      @Override public String toString() {
        return "noEmptyGuardOtherwise";
      }
    };
  }

  private MutationParsers() {}
}
