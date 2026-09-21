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
 * Probes branch outcomes on a given input via the reference interpreter.
 *
 * <p>Used to decide which branches of an {@code or} / {@code longest} are
 * irrelevant (they fail when attempted from the choice's start position and
 * therefore have their input rolled back), so the permutation tests only
 * reorder branches that provably cannot change the result.
 */
final class MiniReferenceProbe {

  private MiniReferenceProbe() {}

  static boolean failsFromStart(MiniGrammar grammar, String packedInput) {
    return MiniReference.run(grammar, packedInput).success == false;
  }
}
