package org.jparsec;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic generator of small {@link MiniGrammar} trees and near-miss inputs.
 *
 * <p>Generated grammars deliberately contain shared prefixes (the same subtree is
 * reused across {@code or}/{@code longest} branches), nullable branches
 * ({@code many} bodies, {@code peek}), nested backtracking ({@code atomic} inside
 * {@code or} inside {@code seq}) and multiple failure points (several literals that
 * can fail at the same or deeper positions). All randomness flows from a single
 * fixed seed, so every case is replayable.
 */
final class MiniGrammarGen {

  private static final char[] ALPHABET = {'a', 'b', 'c'};

  private final Random random;

  MiniGrammarGen(long seed) {
    this.random = new Random(seed);
  }

  MiniGrammar nextGrammar(int maxDepth) {
    return grammar(maxDepth);
  }

  private MiniGrammar grammar(int depth) {
    if (depth <= 0) {
      return leaf();
    }
    switch (random.nextInt(10)) {
      case 0:
      case 1:
        return MiniGrammar.seq(grammar(depth - 1), grammar(depth - 1));
      case 2: {
        // Shared-prefix or: the same subtree starts two branches.
        MiniGrammar shared = grammar(depth - 1);
        return MiniGrammar.or(
            MiniGrammar.seq(shared, grammar(depth - 1)),
            MiniGrammar.seq(shared, grammar(depth - 1)));
      }
      case 3:
        return MiniGrammar.or(grammar(depth - 1), grammar(depth - 1));
      case 4: {
        MiniGrammar shared = grammar(depth - 1);
        return MiniGrammar.longest(
            MiniGrammar.seq(shared, grammar(depth - 1)),
            MiniGrammar.seq(shared, grammar(depth - 1)));
      }
      case 5:
        return MiniGrammar.longest(grammar(depth - 1), grammar(depth - 1));
      case 6:
        return MiniGrammar.atomic(grammar(depth - 1));
      case 7:
        return MiniGrammar.peek(grammar(depth - 1));
      case 8:
        // Nullable branch: many() can succeed consuming nothing.
        return MiniGrammar.many(grammar(depth - 1));
      default:
        return leaf();
    }
  }

  private MiniGrammar leaf() {
    if (random.nextInt(4) == 0) {
      return MiniGrammar.expect("e" + random.nextInt(3));
    }
    return MiniGrammar.lit(ALPHABET[random.nextInt(ALPHABET.length)]);
  }

  /**
   * Generates inputs for {@code grammar}: purely random strings plus near-miss
   * strings derived from the grammar's own literals (truncated, replaced, appended),
   * so that shared prefixes are partially matched and several branches fail at
   * different positions.
   */
  List<String> nextInputs(MiniGrammar grammar, int count) {
    List<String> inputs = new ArrayList<String>();
    inputs.add(randomInput());
    String sketch = sketch(grammar);
    if (!sketch.isEmpty()) {
      inputs.add(sketch);
      inputs.add(sketch.substring(0, sketch.length() - 1));
      inputs.add(replaceChar(sketch));
      inputs.add(sketch + ALPHABET[random.nextInt(ALPHABET.length)]);
    }
    while (inputs.size() < count) {
      inputs.add(randomInput());
    }
    return inputs.subList(0, count);
  }

  private String randomInput() {
    int length = random.nextInt(6);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < length; i++) {
      sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
    }
    return sb.toString();
  }

  private String replaceChar(String s) {
    int at = random.nextInt(s.length());
    char c = ALPHABET[random.nextInt(ALPHABET.length)];
    return s.substring(0, at) + c + s.substring(at + 1);
  }

  /** Walks the leftmost spine, collecting literal characters as a candidate input. */
  private static String sketch(MiniGrammar node) {
    StringBuilder sb = new StringBuilder();
    collect(node, sb, 8);
    return sb.toString();
  }

  private static void collect(MiniGrammar node, StringBuilder sb, int budget) {
    if (budget <= 0 || sb.length() >= 6) return;
    if (node instanceof MiniGrammar.Lit) {
      sb.append(((MiniGrammar.Lit) node).c);
    } else if (node instanceof MiniGrammar.Seq) {
      MiniGrammar.Seq seq = (MiniGrammar.Seq) node;
      collect(seq.left, sb, budget - 1);
      collect(seq.right, sb, budget - 1);
    } else if (node instanceof MiniGrammar.Or) {
      MiniGrammar.Or or = (MiniGrammar.Or) node;
      if (or.branches.length > 0) collect(or.branches[0], sb, budget - 1);
    } else if (node instanceof MiniGrammar.Longest) {
      MiniGrammar.Longest longest = (MiniGrammar.Longest) node;
      if (longest.branches.length > 0) collect(longest.branches[0], sb, budget - 1);
    } else if (node instanceof MiniGrammar.Atomic) {
      collect(((MiniGrammar.Atomic) node).body, sb, budget - 1);
    } else if (node instanceof MiniGrammar.Peek) {
      collect(((MiniGrammar.Peek) node).body, sb, budget - 1);
    } else if (node instanceof MiniGrammar.Many) {
      collect(((MiniGrammar.Many) node).body, sb, budget - 1);
    }
  }
}
