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
import java.util.Objects;

import org.jparsec.error.ParseErrorDetails;
import org.jparsec.error.ParserException;

/**
 * Replayable evidence of a single parse, for both levels and both modes.
 *
 * <p>Instead of only comparing the returned value, an evidence captures the
 * full state boundary of a run:
 * <ul>
 *   <li>success flag and returned value,
 *   <li>physical index of the furthest error (0-based in the rendered source),
 *   <li>the ordered, de-duplicated set of "expected" names at that index,
 *   <li>the unexpected token and the explicit failure message,
 *   <li>the name of the physical token encountered at the error position.
 * </ul>
 * Evidence objects have a precise {@link #equals} / {@link #hashCode} and a
 * diagnostic {@link #toString}, so a mismatch shows exactly which part of the
 * state boundary diverged.
 */
final class ParseEvidence {

  final boolean success;
  final String value;
  final int errorIndex;
  final List<String> expected;
  final String unexpected;
  final String failureMessage;
  final String encountered;

  private ParseEvidence(boolean success, String value, int errorIndex, List<String> expected,
      String unexpected, String failureMessage, String encountered) {
    this.success = success;
    this.value = value;
    this.errorIndex = errorIndex;
    this.expected = Collections.unmodifiableList(new ArrayList<>(expected));
    this.unexpected = unexpected;
    this.failureMessage = failureMessage;
    this.encountered = encountered;
  }

  static ParseEvidence success(String value) {
    return new ParseEvidence(true, value, -1, Collections.<String>emptyList(),
        null, null, null);
  }

  static ParseEvidence failure(int errorIndex, List<String> expected,
      String unexpected, String failureMessage, String encountered) {
    return new ParseEvidence(false, null, errorIndex, expected,
        unexpected, failureMessage, encountered);
  }

  /** Runs a character-level parser on the packed logical source. */
  static ParseEvidence runChar(Parser<String> parser, String packedSource, Parser.Mode mode) {
    return runPublic(parser, packedSource, mode);
  }

  /**
   * Runs a token-level parser directly on a token array, threaded through the
   * package-private {@link ParserState}. The parser itself already carries EOF.
   */
  static ParseEvidence runTokens(
      Parser<String> parser, CharSequence renderedSource, List<Token> tokens, Parser.Mode mode) {
    Token[] input = tokens.toArray(new Token[0]);
    int endIndex = renderedSource.length();
    ParserState state = new ParserState(
        null, renderedSource, input, 0, new SourceLocator(renderedSource), endIndex, null);
    if (mode == Parser.Mode.DEBUG) {
      state.enableTrace("root");
    }
    try {
      String value = state.run(parser);
      return ParseEvidence.success(value);
    } catch (ParserException e) {
      return fromException(e);
    }
  }

  /** Runs through the public {@link Parser#parse(CharSequence, Parser.Mode)} entry point. */
  static ParseEvidence runPublic(Parser<String> parser, String source, Parser.Mode mode) {
    try {
      return ParseEvidence.success(parser.parse(source, mode));
    } catch (ParserException e) {
      return fromException(e);
    }
  }

  private static ParseEvidence fromException(ParserException e) {
    ParseErrorDetails details = e.getErrorDetails();
    if (details == null) {
      throw e;
    }
    return ParseEvidence.failure(
        details.getIndex(),
        details.getExpected() == null
            ? Collections.<String>emptyList()
            : new ArrayList<>(details.getExpected()),
        details.getUnexpected(),
        details.getFailureMessage(),
        details.getEncountered());
  }

  /** Expected names compared as a set: branch order must not change the merged set. */
  boolean sameExceptExpectedOrder(ParseEvidence that) {
    return this.success == that.success
        && Objects.equals(this.value, that.value)
        && this.errorIndex == that.errorIndex
        && Objects.equals(this.unexpected, that.unexpected)
        && Objects.equals(this.failureMessage, that.failureMessage)
        && Objects.equals(this.encountered, that.encountered)
        && this.expected.size() == that.expected.size()
        && this.expected.containsAll(that.expected)
        && that.expected.containsAll(this.expected);
  }

  @Override public boolean equals(Object obj) {
    if (!(obj instanceof ParseEvidence)) return false;
    ParseEvidence that = (ParseEvidence) obj;
    return this.success == that.success
        && Objects.equals(this.value, that.value)
        && this.errorIndex == that.errorIndex
        && Objects.equals(this.expected, that.expected)
        && Objects.equals(this.unexpected, that.unexpected)
        && Objects.equals(this.failureMessage, that.failureMessage)
        && Objects.equals(this.encountered, that.encountered);
  }

  @Override public int hashCode() {
    return Objects.hash(success, value, errorIndex, expected, unexpected, failureMessage,
        encountered);
  }

  @Override public String toString() {
    if (success) return "SUCCESS(" + value + ")";
    StringBuilder builder = new StringBuilder("FAILURE@").append(errorIndex);
    builder.append(" expected=").append(expected);
    if (unexpected != null) builder.append(" unexpected=").append(unexpected);
    if (failureMessage != null) builder.append(" failure=").append(failureMessage);
    if (encountered != null) builder.append(" encountered=").append(encountered);
    return builder.toString();
  }
}
