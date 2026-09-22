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
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jparsec.Parser;
import org.jparsec.Parsers;
import org.jparsec.Scanners;
import org.jparsec.Token;
import org.jparsec.TokenMap;
import org.jparsec.error.ParseErrorDetails;
import org.jparsec.error.ParserException;

/**
 * Compiles a {@link G} grammar into the real {@code org.jparsec} combinators and runs it,
 * capturing the same {@link Outcome} shape that {@link ReferenceMachine} produces.
 *
 * <p>Two input levels are supported so that token-level and character-level error positions
 * can be checked against one another:
 * <ul>
 * <li>{@link #CHAR} reads one symbol per character (alphabet is single letters).
 * <li>{@link #TOKEN} splits the input on spaces and feeds one {@link Token} per word via
 *     {@link Parser#from(Parser, Parser)}; token start indexes must still point into the
 *     original source.
 * </ul>
 */
final class JParsecMachine {

  enum Level { CHAR, TOKEN }

  static Outcome run(G grammar, String source, Level level) {
    Parser<String> parser = compile(grammar, level).followedBy(Parsers.EOF).cast();
    Parser<String> runner = level == Level.CHAR
        ? parser
        : parser.from(tokenizeWords(), Scanners.isChar(' ').skipMany());
    try {
      runner.parse(source);
      return Outcome.succeeded(source.length());
    } catch (ParserException e) {
      return toOutcome(e);
    }
  }

  /** Compiles a single grammar node without the trailing EOF guard (test/mutation hooks). */
  static Parser<String> parserOf(G grammar, Level level) {
    return compile(grammar, level);
  }

  /** Runs an externally assembled parser on a source and captures its {@link Outcome}. */
  static Outcome runParser(Parser<?> parser, String source, Level level) {
    Parser<String> wrapped = parser.followedBy(Parsers.EOF).cast();
    Parser<String> runner = level == Level.CHAR
        ? wrapped
        : wrapped.from(tokenizeWords(), Scanners.isChar(' ').skipMany());
    try {
      runner.parse(source);
      return Outcome.succeeded(source.length());
    } catch (ParserException e) {
      return toOutcome(e);
    }
  }

  private static Outcome toOutcome(ParserException e) {
    ParseErrorDetails details = e.getErrorDetails();
    int index = details == null ? -1 : details.getIndex();
    String failure = details == null ? null : details.getFailureMessage();
    if (failure != null) {
      return Outcome.failure(index, failure);
    }
    String unexpected = details == null ? null : details.getUnexpected();
    if (unexpected != null) {
      return Outcome.unexpected(index, unexpected);
    }
    Set<String> expected = new LinkedHashSet<>();
    if (details != null && details.getExpected() != null) {
      expected.addAll(details.getExpected());
    }
    return Outcome.expecting(index, expected);
  }

  private static Parser<String> compile(G g, Level level) {
    if (g instanceof G.Lit) {
      return terminal(((G.Lit) g).name, level);
    }
    if (g instanceof G.Empty) {
      return Parsers.constant("");
    }
    if (g instanceof G.Fail) {
      return Parsers.fail(((G.Fail) g).name);
    }
    if (g instanceof G.Expect) {
      return Parsers.expect(((G.Expect) g).name);
    }
    if (g instanceof G.Seq) {
      List<Parser<?>> ps = new ArrayList<>();
      for (G child : ((G.Seq) g).children) ps.add(compile(child, level));
      return Parsers.sequence(ps.toArray(new Parser<?>[0])).retn(tag(g));
    }
    if (g instanceof G.Or) {
      List<Parser<String>> ps = new ArrayList<>();
      for (G child : ((G.Or) g).children) ps.add(compile(child, level));
      return Parsers.or(ps.toArray(new Parser[0])).cast();
    }
    if (g instanceof G.Best) {
      List<Parser<String>> ps = new ArrayList<>();
      for (G child : ((G.Best) g).children) ps.add(compile(child, level));
      Parser<String>[] array = ps.toArray(new Parser[0]);
      return ((G.Best) g).longest ? Parsers.longest(array) : Parsers.shortest(array);
    }
    if (g instanceof G.Atomic) {
      return compile(((G.Atomic) g).child, level).atomic();
    }
    if (g instanceof G.Peek) {
      return compile(((G.Peek) g).child, level).peek();
    }
    if (g instanceof G.Not) {
      Parser<String> child = compile(((G.Not) g).child, level);
      return child.not(unexpectedName(((G.Not) g).child)).retn("");
    }
    if (g instanceof G.Optional) {
      return compile(((G.Optional) g).child, level).optional("");
    }
    if (g instanceof G.Otherwise) {
      G.Otherwise o = (G.Otherwise) g;
      return compile(o.primary, level).otherwise(compile(o.fallback, level));
    }
    if (g instanceof G.Many) {
      return compile(((G.Many) g).child, level).many().retn("");
    }
    throw new AssertionError("unknown node " + g.getClass());
  }

  /**
   * Terminal on a given level. At character level a literal matches one character; at token
   * level it matches one token whose value equals the literal. Both report the literal as the
   * expected subject on failure, which keeps expected sets directly comparable across levels.
   */
  static Parser<String> terminal(String name, Level level) {
    if (level == Level.CHAR) {
      return Scanners.string(name).retn(name);
    }
    return Parsers.token(new TokenMap<String>() {
      @Override public String map(Token token) {
        return name.equals(String.valueOf(token.value())) ? name : null;
      }
      @Override public String toString() {
        return name;
      }
    });
  }

  /** Name reported by {@code not()} when its child succeeds; mirrors Parser.not(String). */
  private static String unexpectedName(G child) {
    if (child instanceof G.Lit) return ((G.Lit) child).name;
    return G.show(child);
  }

  /**
   * Character-level lexer: each space-delimited word becomes one token whose value is the
   * word itself. Token start indexes are preserved from the original source.
   */
  private static Parser<Token> tokenizeWords() {
    Parser<?> word = Scanners.isChar(c -> c != ' ').many1().source();
    return word.token();
  }

  private static String tag(G g) {
    return G.show(g);
  }

  private JParsecMachine() {}
}
