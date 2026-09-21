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
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and       *
 * limitations under the License.                                            *
 *****************************************************************************/
package org.jparsec;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds branch-permuted copies of a {@link MiniGrammar}.
 *
 * <p>A branch is only considered "irrelevant" for a given input if the
 * reference interpreter proves that it fails when tried from the choice's
 * starting position (failed branches roll the input back in both {@code or}
 * and {@code longest}/{@code shortest}, so their order cannot affect the
 * observable result or furthest error). Swapping two such failing branches
 * must therefore leave the evidence unchanged apart from the insertion order
 * of merged expected names.
 */
final class BranchPermutation {

  private BranchPermutation() {}

  static MiniGrammar swapFirstTwoFailingChildren(MiniGrammar grammar) {
    return swap(grammar, false, null);
  }

  static MiniGrammar swapFirstTwoFailingChildren(MiniGrammar grammar, String input) {
    return swap(grammar, false, input);
  }

  static MiniGrammar swapFirstTwoFailingBestChildren(MiniGrammar grammar, String input) {
    return swap(grammar, true, input);
  }

  /** Swaps the first two children of the first choice node (any kind). */
  static MiniGrammar swapChildren(MiniGrammar root, int occurrence) {
    MiniGrammar.Seq full = (MiniGrammar.Seq) root;
    int[] counter = {occurrence};
    MiniGrammar body = swapFirstChoice(full.children.get(0), counter);
    List<MiniGrammar> children = new ArrayList<>(full.children);
    children.set(0, body);
    return new MiniGrammar.Seq(children);
  }

  private static MiniGrammar swapFirstChoice(MiniGrammar node, int[] counter) {
    if (node instanceof MiniGrammar.Or) {
      MiniGrammar.Or or = (MiniGrammar.Or) node;
      if (counter[0]-- == 0) {
        List<MiniGrammar> copy = new ArrayList<>(or.children);
        java.util.Collections.swap(copy, 0, 1);
        return new MiniGrammar.Or(copy);
      }
      List<MiniGrammar> mapped = mapAll(or.children, false, null);
      return mapped == null ? node : new MiniGrammar.Or(mapped);
    }
    if (node instanceof MiniGrammar.Best) {
      MiniGrammar.Best choice = (MiniGrammar.Best) node;
      if (counter[0]-- == 0) {
        List<MiniGrammar> copy = new ArrayList<>(choice.children);
        java.util.Collections.swap(copy, 0, 1);
        return new MiniGrammar.Best(copy, choice.longest);
      }
      List<MiniGrammar> mapped = mapAll(choice.children, true, null);
      return mapped == null ? node : new MiniGrammar.Best(mapped, choice.longest);
    }
    if (node instanceof MiniGrammar.Seq) {
      MiniGrammar.Seq seq = (MiniGrammar.Seq) node;
      List<MiniGrammar> mapped = mapAll(seq.children, false, null);
      return mapped == null ? node : new MiniGrammar.Seq(mapped);
    }
    return node;
  }

  private static MiniGrammar swap(MiniGrammar root, boolean best, String input) {
    MiniGrammar.Seq full = (MiniGrammar.Seq) root;
    MiniGrammar body = full.children.get(0);
    MiniGrammar swappedBody = swapRec(body, best, input);
    if (swappedBody == body) return null;
    List<MiniGrammar> children = new ArrayList<>(full.children);
    children.set(0, swappedBody);
    return new MiniGrammar.Seq(children);
  }

  private static MiniGrammar swapRec(MiniGrammar node, boolean best, String input) {
    if (node instanceof MiniGrammar.Seq) {
      MiniGrammar.Seq seq = (MiniGrammar.Seq) node;
      List<MiniGrammar> mapped = mapAll(seq.children, best, input);
      return mapped == null ? node : new MiniGrammar.Seq(mapped);
    }
    if (node instanceof MiniGrammar.Or && !best) {
      return swapOr((MiniGrammar.Or) node, input);
    }
    if (node instanceof MiniGrammar.Best && best) {
      return swapBest((MiniGrammar.Best) node, input);
    }
    return node;
  }

  private static MiniGrammar swapOr(MiniGrammar.Or or, String input) {
    List<MiniGrammar> children = or.children;
    if (children.size() < 2) return or;
    if (input != null) {
      int first = -1;
      int second = -1;
      for (int i = 0; i < children.size(); i++) {
        if (MiniReferenceProbe.failsFromStart(children.get(i), input)) {
          if (first < 0) first = i;
          else if (second < 0) second = i;
        }
      }
      if (second < 0) {
        List<MiniGrammar> mapped = mapAll(children, false, input);
        return mapped == null ? or : new MiniGrammar.Or(mapped);
      }
      List<MiniGrammar> copy = new ArrayList<>(children);
      java.util.Collections.swap(copy, first, second);
      return new MiniGrammar.Or(copy);
    }
    List<MiniGrammar> copy = new ArrayList<>(children);
    java.util.Collections.swap(copy, 0, 1);
    return new MiniGrammar.Or(copy);
  }

  private static MiniGrammar swapBest(MiniGrammar.Best choice, String input) {
    List<MiniGrammar> children = choice.children;
    int first = -1;
    int second = -1;
    for (int i = 0; i < children.size(); i++) {
      if (MiniReferenceProbe.failsFromStart(children.get(i), input)) {
        if (first < 0) first = i;
        else if (second < 0) second = i;
      }
    }
    if (second < 0) {
      List<MiniGrammar> mapped = mapAll(children, true, input);
      return mapped == null ? choice : new MiniGrammar.Best(mapped, choice.longest);
    }
    List<MiniGrammar> copy = new ArrayList<>(children);
    java.util.Collections.swap(copy, first, second);
    return new MiniGrammar.Best(copy, choice.longest);
  }

  /** Returns a new child list if any descendant changed, otherwise null. */
  private static List<MiniGrammar> mapAll(List<MiniGrammar> nodes, boolean best, String input) {
    List<MiniGrammar> mapped = new ArrayList<>();
    boolean changed = false;
    for (MiniGrammar child : nodes) {
      MiniGrammar result = swapRec(child, best, input);
      mapped.add(result);
      if (result != child) changed = true;
    }
    return changed ? mapped : null;
  }
}
