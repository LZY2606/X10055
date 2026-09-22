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

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Replayable evidence of a single parser run.
 *
 * <p>The property tests never compare a bare return value; they compare the full outcome:
 * success/failure, the physical source position reached or reported, and the diagnostic
 * payload (expected set or explicit failure message).
 */
final class Outcome {

  enum Kind { EXPECTING, FAILURE, UNEXPECTED, NONE }

  final boolean success;
  final int position;
  final Kind errorKind;
  final Set<String> expected;
  /** Failure message for {@link Kind#FAILURE}, unexpected subject for {@link Kind#UNEXPECTED}. */
  final String failureMessage;

  private Outcome(boolean success, int position, Kind errorKind,
      Set<String> expected, String failureMessage) {
    this.success = success;
    this.position = position;
    this.errorKind = errorKind;
    this.expected = expected;
    this.failureMessage = failureMessage;
  }

  static Outcome succeeded(int position) {
    return new Outcome(true, position, Kind.NONE, new LinkedHashSet<>(), null);
  }

  static Outcome expecting(int position, Set<String> expected) {
    return new Outcome(false, position, Kind.EXPECTING,
        new LinkedHashSet<>(expected), null);
  }

  static Outcome failure(int position, String message) {
    return new Outcome(false, position, Kind.FAILURE,
        new LinkedHashSet<>(), message);
  }

  static Outcome unexpected(int position, String subject) {
    return new Outcome(false, position, Kind.UNEXPECTED,
        new LinkedHashSet<>(), subject);
  }

  @Override public boolean equals(Object obj) {
    if (!(obj instanceof Outcome)) return false;
    Outcome other = (Outcome) obj;
    return success == other.success
        && position == other.position
        && errorKind == other.errorKind
        && Objects.equals(expected, other.expected)
        && Objects.equals(failureMessage, other.failureMessage);
  }

  @Override public int hashCode() {
    return Objects.hash(success, position, errorKind, expected, failureMessage);
  }

  @Override public String toString() {
    if (success) return "success@" + position;
    if (errorKind == Kind.FAILURE) {
      return "failure@" + position + "[" + failureMessage + "]";
    }
    if (errorKind == Kind.UNEXPECTED) {
      return "unexpected@" + position + "[" + failureMessage + "]";
    }
    return "error@" + position + " expected=" + expected;
  }
}
