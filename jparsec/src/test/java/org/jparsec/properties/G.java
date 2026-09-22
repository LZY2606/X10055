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
 * A tiny, closed grammar language used by the backtracking/error-merging property tests.
 *
 * <p>The same grammar description is interpreted two independent ways:
 * <ol>
 * <li>Compiled to the real {@code org.jparsec} combinators under test.
 * <li>Evaluated by {@link ReferenceMachine}, a from-scratch interpreter that mirrors the
 *     documented state semantics (input cursor, logical step, and the single
 *     farthest-error record with merging).
 * </ol>
 *
 * <p>Terminals are single symbols over an alphabet of short strings ({@code "a"}, {@code "b"},
 * {@code "x"} ...). A grammar is linear {@link Seq sequence}, ordered {@link Or choice},
 * longest {@link Best choice}, {@link Atomic atomic} (all-or-nothing + step collapse),
 * {@link Peek lookahead} (zero-width success), {@link Not negative lookahead},
 * {@link Optional optional}, {@link Many zero-or-more}, plus explicit failure/expectation
 * markers and the empty (zero-width success) parser. Shared prefixes, nullable branches
 * and multiple failure points all fall out naturally from these nodes.
 */
abstract class G {

  /** Terminal that matches one input symbol equal to {@code name}. */
  static final class Lit extends G {
    final String name;
    Lit(String name) { this.name = name; }
  }

  /** Zero-width parser that always succeeds. */
  static final class Empty extends G {}

  /** Fails without consuming, reporting a missing terminal (mergeable error). */
  static final class Fail extends G {
    final String name;
    Fail(String name) { this.name = name; }
  }

  /** Fails without consuming, reporting a logical expectation (higher error precedence). */
  static final class Expect extends G {
    final String name;
    Expect(String name) { this.name = name; }
  }

  /** Runs every child in order; succeeds iff all succeed. */
  static final class Seq extends G {
    final List<G> children;
    Seq(List<G> children) { this.children = children; }
  }

  /** Ordered choice: first succeeding branch wins; state is rolled back between trials. */
  static final class Or extends G {
    final List<G> children;
    Or(List<G> children) { this.children = children; }
  }

  /** Longest/shortest choice. All branches are tried; the extreme consumed length wins. */
  static final class Best extends G {
    final List<G> children;
    final boolean longest;
    Best(List<G> children, boolean longest) {
      this.children = children;
      this.longest = longest;
    }
  }

  /**
   * Atomic: on failure restores both the physical cursor and the logical step; on success
   * collapses everything into one logical step.
   */
  static final class Atomic extends G {
    final G child;
    Atomic(G child) { this.child = child; }
  }

  /** Positive lookahead: succeeds like the child but restores the physical cursor. */
  static final class Peek extends G {
    final G child;
    Peek(G child) { this.child = child; }
  }

  /** Negative lookahead: succeeds iff the child fails; child errors are suppressed. */
  static final class Not extends G {
    final G child;
    Not(G child) { this.child = child; }
  }

  /** Succeeds even when the child fails with no partial match. */
  static final class Optional extends G {
    final G child;
    Optional(G child) { this.child = child; }
  }

  /**
   * Runs {@code primary}; falls back to {@code fallback} only when the primary fails
   * <em>at the starting position</em> (zero-width failure). A partial match is fatal.
   */
  static final class Otherwise extends G {
    final G primary;
    final G fallback;
    Otherwise(G primary, G fallback) {
      this.primary = primary;
      this.fallback = fallback;
    }
  }

  /** Zero or more repetitions, stopping at a zero-width match. */
  static final class Many extends G {
    final G child;
    Many(G child) { this.child = child; }
  }

  static Lit lit(String name) { return new Lit(name); }
  static G empty() { return new Empty(); }
  static Fail fail(String name) { return new Fail(name); }
  static Expect expect(String name) { return new Expect(name); }

  static Seq seq(G... gs) {
    List<G> list = new ArrayList<>(gs.length);
    for (G g : gs) list.add(g);
    return new Seq(list);
  }

  static Or or(G... gs) {
    List<G> list = new ArrayList<>(gs.length);
    for (G g : gs) list.add(g);
    return new Or(list);
  }

  static Best longest(G... gs) {
    List<G> list = new ArrayList<>(gs.length);
    for (G g : gs) list.add(g);
    return new Best(list, true);
  }

  static Best shortest(G... gs) {
    List<G> list = new ArrayList<>(gs.length);
    for (G g : gs) list.add(g);
    return new Best(list, false);
  }

  static Atomic atomic(G g) { return new Atomic(g); }
  static Peek peek(G g) { return new Peek(g); }
  static Not not(G g) { return new Not(g); }
  static Optional optional(G g) { return new Optional(g); }
  static Many many(G g) { return new Many(g); }
  static Otherwise otherwise(G primary, G fallback) {
    return new Otherwise(primary, fallback);
  }

  /**
   * A stable, human-readable rendering of a grammar node. Used in failure messages so any
   * counter-example is directly replayable without a debugger.
   */
  static String show(G g) {
    StringBuilder sb = new StringBuilder();
    render(g, sb);
    return sb.toString();
  }

  private static void render(G g, StringBuilder sb) {
    if (g instanceof Lit) {
      sb.append('\'').append(((Lit) g).name).append('\'');
    } else if (g instanceof Empty) {
      sb.append("empty");
    } else if (g instanceof Fail) {
      sb.append("fail(").append(((Fail) g).name).append(')');
    } else if (g instanceof Expect) {
      sb.append("expect(").append(((Expect) g).name).append(')');
    } else if (g instanceof Seq) {
      renderChildren("seq", ((Seq) g).children, sb);
    } else if (g instanceof Or) {
      renderChildren("or", ((Or) g).children, sb);
    } else if (g instanceof Best) {
      renderChildren(((Best) g).longest ? "longest" : "shortest",
          ((Best) g).children, sb);
    } else if (g instanceof Atomic) {
      sb.append("atomic(");
      render(((Atomic) g).child, sb);
      sb.append(')');
    } else if (g instanceof Peek) {
      sb.append("peek(");
      render(((Peek) g).child, sb);
      sb.append(')');
    } else if (g instanceof Not) {
      sb.append("not(");
      render(((Not) g).child, sb);
      sb.append(')');
    } else if (g instanceof Optional) {
      sb.append("optional(");
      render(((Optional) g).child, sb);
      sb.append(')');
    } else if (g instanceof Many) {
      sb.append("many(");
      render(((Many) g).child, sb);
      sb.append(')');
    } else if (g instanceof Otherwise) {
      sb.append("otherwise(");
      render(((Otherwise) g).primary, sb);
      sb.append(", ");
      render(((Otherwise) g).fallback, sb);
      sb.append(')');
    } else {
      throw new AssertionError("unknown node " + g.getClass());
    }
  }

  private static void renderChildren(String name, List<G> children, StringBuilder sb) {
    sb.append(name).append('(');
    for (int i = 0; i < children.size(); i++) {
      if (i > 0) sb.append(", ");
      render(children.get(i), sb);
    }
    sb.append(')');
  }

  private G() {}
}
