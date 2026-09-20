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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.jparsec.error.ParseErrorDetails;
import org.jparsec.error.ParserException;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Property tests for parser-combinator choice, backtracking and error-merging semantics.
 *
 * <p>The tested surface is the interaction of {@link Parsers#or}, {@link Parsers#longest},
 * {@link Parser#atomic()} and {@link Parser#peek()} (lookahead) over grammars that deliberately
 * contain shared prefixes, nullable branches, nested backtracking and multiple failure points.
 *
 * <p>Every property is checked against an independent reference interpreter ({@link Ref}) that
 * models input position, logical step and the "furthest error wins, equal-position errors merge"
 * rule implemented by {@code ParseContext}. The same abstract grammar is interpreted both by the
 * reference machine and compiled to a real jparsec {@link Parser}, at both character level and
 * token level, so token-level error positions are verified to map back to the same physical
 * source index the character-level machine reports.
 */
public final class BacktrackingPropertyTest {

  // ---------------------------------------------------------------------------
  // Abstract grammar
  //
  // Terminals live on a fixed alphabet {a, b, c, EOF} so that generated grammars have
  // frequent shared prefixes. Each terminal carries an explicit "expect" name.
  // ---------------------------------------------------------------------------

  static final char[] ALPHABET = {'a', 'b', 'c'};
  static final String EOF = "EOF";

  abstract static class Node {
    Node() {}
  }

  /** Matches a single symbol equal to {@code ch}; fails without consuming otherwise. */
  static final class Term extends Node {
    final char ch;
    Term(char ch) { this.ch = ch; }
    String expectName() { return String.valueOf(ch); }
  }

  /** Epsilon: always succeeds, consumes nothing. */
  static final class Empty extends Node {}

  static final class Seq extends Node {
    final Node left, right;
    Seq(Node left, Node right) { this.left = left; this.right = right; }
  }

  /** Ordered choice: first success wins; fallback happens regardless of partial match. */
  static final class Or extends Node {
    final List<Node> branches;
    Or(List<Node> branches) { this.branches = branches; }
  }

  /** Unordered choice: the branch consuming the most input wins; ties favor the first. */
  static final class Longest extends Node {
    final List<Node> branches;
    Longest(List<Node> branches) { this.branches = branches; }
  }

  /** Atomic: on failure the (physical, logical) position is rolled back. */
  static final class Atomic extends Node {
    final Node inner;
    Atomic(Node inner) { this.inner = inner; }
  }

  /** Lookahead: success rewinds the consumed position; failure leaves it untouched. */
  static final class Peek extends Node {
    final Node inner;
    Peek(Node inner) { this.inner = inner; }
  }

  // ---------------------------------------------------------------------------
  // Reference interpreter
  // ---------------------------------------------------------------------------

  /** Outcome observable through jparsec's public API. */
  static final class Outcome {
    final boolean success;
    /** Consumed physical length (index of the parse frontier on success). */
    final int consumed;
    /** Physical index of the furthest recorded error (failure only). */
    final int errorIndex;
    /** Merged "expected" names at the furthest error (failure only). */
    final Set<String> expected;

    private Outcome(boolean success, int consumed, int errorIndex, Set<String> expected) {
      this.success = success;
      this.consumed = consumed;
      this.errorIndex = errorIndex;
      this.expected = expected;
    }

    static Outcome ok(int consumed) {
      return new Outcome(true, consumed, -1, Collections.<String>emptySet());
    }

    static Outcome fail(int errorIndex, Set<String> expected) {
      return new Outcome(false, -1, errorIndex, expected);
    }

    @Override public String toString() {
      return success
          ? "OK(consumed=" + consumed + ")"
          : "FAIL(index=" + errorIndex + ", expected=" + expected + ")";
    }
  }

  /**
   * A small step-indexed abstract machine. {@code symbols} maps logical symbol position to
   * physical source index; logical position {@code n} equals {@code symbols.length} at EOF and
   * maps to the source length.
   */
  static final class Ref {
    private final char[] input;
    private final int[] symbols;
    private final int endIndex;

    // mutable machine state
    private int at;
    private int step;
    // Furthest error keyed by physical source index, mirroring ParseContext:
    // currentErrorIndex starts at the entry index and expected names only attach
    // once a raise actually targets that (or a later) index.
    private int errIndex;
    private final List<String> errors = new ArrayList<String>();

    Ref(char[] input, int[] symbols, int endIndex) {
      this.input = input;
      this.symbols = symbols;
      this.endIndex = endIndex;
      // Both the character and the nested token-level state start with an error slot
      // at the entry index 0; expected names only attach once a raise targets it.
      this.errIndex = 0;
    }

    private int physical(int logicalPos) {
      // Mirrors ParserState.toIndex: the current token reports its own index, while
      // any position at/past the last token collapses onto a fixed end index.
      return logicalPos >= symbols.length ? endIndex : symbols[logicalPos];
    }

    private int frontierIndex() {
      return physical(at);
    }

    private boolean term(char ch) {
      if (at < symbols.length && input[at] == ch) {
        at++;
        step++;
        return true;
      }
      raise(String.valueOf(ch), at);
      return false;
    }

    private void raise(String name, int logicalPos) {
      int index = physical(logicalPos);
      if (index < errIndex) return; // furthest physical error wins
      if (index > errIndex) {
        errIndex = index;
        errors.clear();
      }
      if (!errors.contains(name)) errors.add(name);
    }

    private boolean eof() {
      if (at == symbols.length) return true;
      raise(EOF, at);
      return false;
    }

    private boolean run(Node node) {
      if (node instanceof Term) {
        return term(((Term) node).ch);
      }
      if (node instanceof Empty) {
        return true;
      }
      if (node instanceof Seq) {
        Seq seq = (Seq) node;
        return run(seq.left) && run(seq.right);
      }
      if (node instanceof Or) {
        Or or = (Or) node;
        int startAt = at;
        int startStep = step;
        for (Node branch : or.branches) {
          at = startAt;
          step = startStep;
          if (run(branch)) return true;
        }
        at = startAt;
        step = startStep;
        return false;
      }
      if (node instanceof Longest) {
        Longest longest = (Longest) node;
        int startAt = at;
        int startStep = step;
        int bestAt = -1;
        int bestStep = -1;
        for (Node branch : longest.branches) {
          at = startAt;
          step = startStep;
          if (run(branch) && at > bestAt) {
            bestAt = at;
            bestStep = step;
          }
        }
        if (bestAt < 0) {
          at = startAt;
          step = startStep;
          return false;
        }
        at = bestAt;
        step = bestStep;
        return true;
      }
      if (node instanceof Atomic) {
        int savedAt0 = at;
        int savedStep0 = step;
        if (run(((Atomic) node).inner)) {
          step = savedStep0 + 1;
          return true;
        }
        at = savedAt0;
        step = savedStep0;
        return false;
      }
      if (node instanceof Peek) {
        int savedAt0 = at;
        int savedStep0 = step;
        boolean ok = run(((Peek) node).inner);
        if (ok) {
          at = savedAt0;
          step = savedStep0;
        }
        return ok;
      }
      throw new AssertionError("unknown node " + node.getClass());
    }

    static Outcome interpret(
        Node grammar, char[] input, int[] symbols, int endIndex) {
      Ref ref = new Ref(input, symbols, endIndex);
      // Parser#from anchors the nested token parser with followedBy(EOF), so the EOF
      // check happens inside the nested state and is copied back by physical index.
      boolean ok = ref.run(grammar) && ref.eof();
      if (ok) return Outcome.ok(ref.frontierIndex());
      Set<String> expected = new LinkedHashSet<String>(ref.errors);
      return Outcome.fail(ref.errIndex, expected);
    }
  }

  // ---------------------------------------------------------------------------
  // Compiling the abstract grammar to real jparsec parsers
  // ---------------------------------------------------------------------------

  /** A character-level terminal that reports a stable "expected" name. */
  static Parser<Void> charTerminal(final char ch) {
    return new Parser<Void>() {
      @Override boolean apply(ParseContext ctxt) {
        if (ctxt.isEof() || ctxt.peekChar() != ch) {
          ctxt.missing(String.valueOf(ch));
          return false;
        }
        ctxt.next();
        ctxt.result = null;
        return true;
      }
      @Override public String toString() { return String.valueOf(ch); }
    };
  }

  /** A token-level terminal matching a token whose value is the boxed character. */
  static Parser<Object> tokenTerminal(final char ch) {
    final Object value = Character.valueOf(ch);
    return Parsers.token(new TokenMap<Object>() {
      @Override public Object map(Token token) {
        return value.equals(token.value()) ? value : null;
      }
      @Override public String toString() { return String.valueOf(ch); }
    });
  }

  @SuppressWarnings("unchecked")
  static Parser<Object> compileToken(Node node) {
    if (node instanceof Term) return (Parser<Object>) (Parser<?>) tokenTerminal(((Term) node).ch);
    if (node instanceof Empty) return Parsers.always();
    if (node instanceof Seq) {
      Seq seq = (Seq) node;
      return Parsers.sequence(compileToken(seq.left), compileToken(seq.right));
    }
    if (node instanceof Or) {
      Or or = (Or) node;
      Parser<Object>[] ps = branchParsers(or.branches, true);
      return Parsers.or(ps);
    }
    if (node instanceof Longest) {
      Longest longest = (Longest) node;
      return Parsers.longest(branchParsers(longest.branches, true));
    }
    if (node instanceof Atomic) return compileToken(((Atomic) node).inner).atomic();
    if (node instanceof Peek) return compileToken(((Peek) node).inner).peek();
    throw new AssertionError(node.getClass());
  }

  @SuppressWarnings("unchecked")
  static Parser<Void> compileChar(Node node) {
    if (node instanceof Term) return charTerminal(((Term) node).ch);
    if (node instanceof Empty) return Parsers.always();
    if (node instanceof Seq) {
      Seq seq = (Seq) node;
      return Parsers.sequence(compileChar(seq.left), compileChar(seq.right));
    }
    if (node instanceof Or) return Parsers.or(branchParsers(((Or) node).branches, false));
    if (node instanceof Longest) return Parsers.longest(branchParsers(((Longest) node).branches, false));
    if (node instanceof Atomic) return compileChar(((Atomic) node).inner).atomic();
    if (node instanceof Peek) return compileChar(((Peek) node).inner).peek();
    throw new AssertionError(node.getClass());
  }

  @SuppressWarnings("unchecked")
  private static <T> Parser<T>[] branchParsers(List<Node> branches, boolean tokenLevel) {
    Parser<?>[] result = new Parser<?>[branches.size()];
    for (int i = 0; i < result.length; i++) {
      result[i] = tokenLevel ? compileToken(branches.get(i)) : compileChar(branches.get(i));
    }
    return (Parser<T>[]) result;
  }

  // ---------------------------------------------------------------------------
  // Random grammar and input generation
  // ---------------------------------------------------------------------------

  static final class Gen {
    private final Random random;
    Gen(long seed) { this.random = new Random(seed); }

    Node grammar(int maxDepth) {
      if (maxDepth <= 0 || random.nextInt(4) == 0) {
        int r = random.nextInt(10);
        if (r < 7) return new Term(ALPHABET[random.nextInt(ALPHABET.length)]);
        return new Empty();
      }
      int kind = random.nextInt(6);
      switch (kind) {
        case 0:
          return new Seq(grammar(maxDepth - 1), grammar(maxDepth - 1));
        case 1:
        case 2:
          return new Or(branches(maxDepth));
        case 3:
          return new Longest(branches(maxDepth));
        case 4:
          return new Atomic(grammar(maxDepth - 1));
        default:
          return new Peek(grammar(maxDepth - 1));
      }
    }

    private List<Node> branches(int maxDepth) {
      int n = 2 + random.nextInt(2); // 2 or 3 branches
      List<Node> nodes = new ArrayList<Node>();
      for (int i = 0; i < n; i++) nodes.add(grammar(maxDepth - 1));
      return nodes;
    }

    char[] input() {
      int len = random.nextInt(5); // 0..4 symbols
      char[] chars = new char[len];
      for (int i = 0; i < len; i++) {
        int r = random.nextInt(12);
        chars[i] = r < ALPHABET.length ? ALPHABET[r] : (char) ('a' + r);
      }
      return chars;
    }
  }

  /** Returns every node reachable from {@code root} (bounded rewrite candidates). */
  static List<Node> nodes(Node root) {
    List<Node> all = new ArrayList<Node>();
    collect(root, all);
    return all;
  }

  private static void collect(Node node, List<Node> out) {
    out.add(node);
    if (node instanceof Seq) {
      collect(((Seq) node).left, out);
      collect(((Seq) node).right, out);
    } else if (node instanceof Or) {
      for (Node branch : ((Or) node).branches) collect(branch, out);
    } else if (node instanceof Longest) {
      for (Node branch : ((Longest) node).branches) collect(branch, out);
    } else if (node instanceof Atomic) {
      collect(((Atomic) node).inner, out);
    } else if (node instanceof Peek) {
      collect(((Peek) node).inner, out);
    }
  }

  /** Structural substitution: replace every occurrence of {@code from} with {@code to}. */
  static Node replace(Node root, Node from, Node to) {
    if (root == from) return to;
    if (root instanceof Seq) {
      Seq seq = (Seq) root;
      return new Seq(replace(seq.left, from, to), replace(seq.right, from, to));
    }
    if (root instanceof Or) {
      List<Node> bs = new ArrayList<Node>();
      for (Node b : ((Or) root).branches) bs.add(replace(b, from, to));
      return new Or(bs);
    }
    if (root instanceof Longest) {
      List<Node> bs = new ArrayList<Node>();
      for (Node b : ((Longest) root).branches) bs.add(replace(b, from, to));
      return new Longest(bs);
    }
    if (root instanceof Atomic) return new Atomic(replace(((Atomic) root).inner, from, to));
    if (root instanceof Peek) return new Peek(replace(((Peek) root).inner, from, to));
    return root;
  }

  // ---------------------------------------------------------------------------
  // Executing the real parser and comparing with the reference interpreter
  // ---------------------------------------------------------------------------

  static final class Case {
    final Node grammar;
    final char[] input;

    Case(Node grammar, char[] input) {
      this.grammar = grammar;
      this.input = input;
    }

    @Override public String toString() {
      return "grammar=" + grammar + ", input=" + Arrays.toString(input);
    }
  }

  /** A single comparison verdict; carries replayable diagnostic context on mismatch. */
  static final class Verdict {
    final boolean holds;
    final String evidence;

    private Verdict(boolean holds, String evidence) {
      this.holds = holds;
      this.evidence = evidence;
    }

    static Verdict check(Case c) {
      String source = new String(c.input);
      // Character level: one symbol per character, logical == physical.
      int[] charSymbols = new int[c.input.length];
      for (int i = 0; i < charSymbols.length; i++) charSymbols[i] = i;
      Outcome refChar = Ref.interpret(c.grammar, c.input, charSymbols, c.input.length);
      Outcome realChar = runChar(compileChar(c.grammar), source);

      // Token level: tokens sit at odd physical offsets with one leading space so that
      // token position != token index; the reported index must still map to the source.
      // Token level: the explicit token list is produced by an empty-consuming lexer,
      // so the outer character source is the empty string and the nested parser starts
      // at outer index 0. Physical positions are carried entirely by Token.index(),
      // which we spread onto odd offsets to prove token ordinal != physical index.
      // The nested EOF anchor collapses onto the same outer index 0 on success.
      int[] tokenSymbols = new int[c.input.length];
      List<Token> tokens = new ArrayList<Token>();
      for (int i = 0; i < c.input.length; i++) {
        int physical = 2 * i + 1;
        tokenSymbols[i] = physical;
        tokens.add(new Token(physical, 1, Character.valueOf(c.input[i])));
      }
      int endIndex = tokens.isEmpty()
          ? 0 : tokens.get(tokens.size() - 1).index() + tokens.get(tokens.size() - 1).length();
      Outcome refToken = Ref.interpret(c.grammar, c.input, tokenSymbols, endIndex);
      Outcome realToken = runTokens(compileToken(c.grammar), tokens);

      String evidence = describe(c, refChar, realChar, refToken, realToken);
      boolean holds =
          equivalent(refChar, realChar) && equivalent(refToken, realToken);
      return new Verdict(holds, evidence);
    }

    private static String describe(
        Case c, Outcome refChar, Outcome realChar, Outcome refToken, Outcome realToken) {
      StringBuilder sb = new StringBuilder();
      sb.append("\n  input chars = ").append(Arrays.toString(c.input));
      sb.append("\n  grammar     = ").append(render(c.grammar));
      sb.append("\n  char-level: reference=").append(refChar)
          .append(" jparsec=").append(realChar);
      sb.append("\n  token-level: reference=").append(refToken)
          .append(" jparsec=").append(realToken);
      return sb.toString();
    }
  }

  static Outcome runChar(Parser<Void> parser, String source) {
    try {
      parser.followedBy(Parsers.EOF).parse(source);
      return Outcome.ok(source.length());
    } catch (ParserException e) {
      ParseErrorDetails details = e.getErrorDetails();
      assertNotNull(details);
      return Outcome.fail(details.getIndex(), normalize(details.getExpected()));
    }
  }

  static Outcome runTokens(Parser<Object> parser, List<Token> tokens) {
    // Build the token-level state directly. This exercises the exact ParserState the
    // library enters after tokenization, without adding a character-level outer EOF
    // (an explicit token-list lexer is empty-consuming and would otherwise anchor the
    // enclosing ScannerState at index 0). The padded source only backs the
    // SourceLocator and covers every token physical index.
    int maxIndex = 0;
    for (Token token : tokens) maxIndex = Math.max(maxIndex, token.index() + token.length());
    String source = blanks(Math.max(maxIndex, 1));
    Token[] input = tokens.toArray(new Token[0]);
    ParserState state = new ParserState(null, source, input, 0,
        new SourceLocator(source), maxIndex, null);
    Parser<Object> anchored = parser.followedBy(Parsers.EOF);
    boolean ok = anchored.apply(state);
    if (ok) return Outcome.ok(state.toIndex(state.at));
    ParseErrorDetails details = state.renderError();
    return Outcome.fail(details.getIndex(), normalize(details.getExpected()));
  }

  private static Set<String> normalize(List<String> expected) {
    Set<String> set = new LinkedHashSet<String>();
    if (expected != null) set.addAll(expected);
    return set;
  }

  static boolean equivalent(Outcome expected, Outcome actual) {
    if (expected.success != actual.success) return false;
    if (expected.success) return expected.consumed == actual.consumed;
    return expected.errorIndex == actual.errorIndex
        && expected.expected.equals(actual.expected);
  }

  // ---------------------------------------------------------------------------
  // Shrinking: on any discrepancy, reduce to the smallest reproducing case.
  // ---------------------------------------------------------------------------

  static Case shrink(Case c) {
    Case current = c;
    boolean changed = true;
    while (changed) {
      changed = false;
      // 1) drop trailing symbols.
      for (int len = current.input.length - 1; len >= 0; len--) {
        char[] shorter = Arrays.copyOf(current.input, len);
        Case candidate = new Case(current.grammar, shorter);
        if (!Verdict.check(candidate).holds) {
          current = candidate;
          changed = true;
          break;
        }
      }
      if (changed) continue;
      // 2) remove an individual symbol (keep the cases where leading space removal shifts indexes).
      for (int i = 0; i < current.input.length; i++) {
        char[] smaller = new char[current.input.length - 1];
        System.arraycopy(current.input, 0, smaller, 0, i);
        System.arraycopy(current.input, i + 1, smaller, i, smaller.length - i);
        Case candidate = new Case(current.grammar, smaller);
        if (!Verdict.check(candidate).holds) {
          current = candidate;
          changed = true;
          break;
        }
      }
      if (changed) continue;
      // 3) simplify grammar: Term -> Empty, wrapper -> inner, drop choice branches.
      List<Node> all = nodes(current.grammar);
      outer:
      for (Node node : all) {
        List<Node> replacements = new ArrayList<Node>();
        replacements.add(new Empty());
        if (node instanceof Atomic) replacements.add(((Atomic) node).inner);
        if (node instanceof Peek) replacements.add(((Peek) node).inner);
        if (node instanceof Seq) {
          replacements.add(((Seq) node).left);
          replacements.add(((Seq) node).right);
        }
        for (Node replacement : replacements) {
          Node smaller = replace(current.grammar, node, replacement);
          Case candidate = new Case(smaller, current.input);
          if (!Verdict.check(candidate).holds) {
            current = candidate;
            changed = true;
            break outer;
          }
        }
      }
    }
    return current;
  }

  // ---------------------------------------------------------------------------
  // Grammar rendering (diagnostic context only)
  // ---------------------------------------------------------------------------

  static String render(Node node) {
    if (node instanceof Term) return String.valueOf(((Term) node).ch);
    if (node instanceof Empty) return "eps";
    if (node instanceof Seq) {
      Seq seq = (Seq) node;
      return "(" + render(seq.left) + " " + render(seq.right) + ")";
    }
    if (node instanceof Or) return renderChoice("or", ((Or) node).branches);
    if (node instanceof Longest) return renderChoice("longest", ((Longest) node).branches);
    if (node instanceof Atomic) return "atomic(" + render(((Atomic) node).inner) + ")";
    return "peek(" + render(((Peek) node).inner) + ")";
  }

  private static String renderChoice(String name, List<Node> branches) {
    StringBuilder sb = new StringBuilder(name).append('(');
    for (int i = 0; i < branches.size(); i++) {
      if (i > 0) sb.append(", ");
      sb.append(render(branches.get(i)));
    }
    return sb.append(')').toString();
  }

  // ---------------------------------------------------------------------------
  // Properties
  // ---------------------------------------------------------------------------

  private static final int GENERATED_CASES = 1200;
  private static final long FIXED_SEED = 9012680L;

  /**
   * Core generative property: for every generated grammar/input pair the real jparsec
   * parser agrees with {@link Ref} on success vs. failure, the consumed frontier, the
   * furthest error index and the merged "expected" set, at both character and token level.
   */
  @Test(timeout = 30_000)
  public void generatedGrammarsMatchReferenceInterpreter() {
    Gen gen = new Gen(FIXED_SEED);
    for (int i = 0; i < GENERATED_CASES; i++) {
      Case c = new Case(gen.grammar(4), gen.input());
      Verdict verdict = Verdict.check(c);
      if (!verdict.holds) {
        Case minimal = shrink(c);
        fail("reference mismatch (case " + i + "): " + verdict.evidence
            + "\n  shrunk to: " + Verdict.check(minimal).evidence);
      }
    }
  }

  /**
   * Permutation property: swapping around alternatives that cannot affect the observable
   * outcome must leave the result untouched. For {@code or} this means equivalent-length
   * branches and the all-failing case; for {@code longest} every permutation is allowed.
   */
  @Test(timeout = 30_000)
  public void swappingIrrelevantAlternativesDoesNotChangeTheOutcome() {
    Random random = new Random(FIXED_SEED ^ 0x5a5a);
    Gen gen = new Gen(FIXED_SEED ^ 0x5a5a);
    for (int i = 0; i < 400; i++) {
      char[] input = gen.input();

      // longest: every branch ordering is observable-equivalent.
      List<Node> branches = new ArrayList<Node>();
      branches.add(new Seq(new Term('a'), new Term('b')));       // consumes "ab"
      branches.add(new Seq(new Seq(new Term('a'), new Term('b')), new Term('c'))); // "abc"
      branches.add(new Term('a'));                                                  // "a"
      assertPermutationsAgree(new Longest(branches), input, /*orderedChoice=*/false, random);

      // or: branches are kept permutation-safe by giving each the same prefix length
      // and making them succeed/fail identically over the generated alphabet.
      List<Node> orBranches = new ArrayList<Node>();
      orBranches.add(new Seq(new Term('a'), new Term('b')));
      orBranches.add(new Seq(new Term('a'), new Term('b')));
      orBranches.add(new Seq(new Term('a'), new Term('b')));
      assertPermutationsAgree(new Or(orBranches), input, /*orderedChoice=*/true, random);
    }
  }

  private static void assertPermutationsAgree(
      Node grammar, char[] input, boolean orderedChoice, Random random) {
    Outcome first = realOutcome(grammar, input);
    for (int attempt = 0; attempt < 4; attempt++) {
      Node permuted = permute(grammar, random);
      Outcome other = realOutcome(permuted, input);
      assertTrue("permuted choice changed outcome: " + render(grammar)
          + " -> " + render(permuted) + " input=" + Arrays.toString(input)
          + " first=" + first + " other=" + other,
          permutationEquivalent(first, other, orderedChoice));
    }
  }

  private static boolean permutationEquivalent(Outcome a, Outcome b, boolean orderedChoice) {
    if (a.success != b.success) return false;
    if (a.success) {
      // For an ordered choice only equal-length branches were generated, so the
      // consumed frontier is invariant even though the selected branch may differ.
      return a.consumed == b.consumed;
    }
    return a.errorIndex == b.errorIndex && a.expected.equals(b.expected);
  }

  private static Outcome realOutcome(Node grammar, char[] input) {
    int[] symbols = new int[input.length];
    for (int i = 0; i < symbols.length; i++) symbols[i] = i;
    return runChar(compileChar(grammar), new String(input));
  }

  private static Node permute(Node grammar, Random random) {
    List<Node> branches = grammar instanceof Or
        ? new ArrayList<Node>(((Or) grammar).branches)
        : new ArrayList<Node>(((Longest) grammar).branches);
    Collections.shuffle(branches, random);
    return grammar instanceof Or ? new Or(branches) : new Longest(branches);
  }

  /**
   * Explicit boundary matrix (truth table) for the four combinators over shared
   * prefixes, nullable branches, nested backtracking and multiple failure points.
   * Each row is independently enumerated below rather than generated.
   */
  @Test(timeout = 30_000)
  public void boundaryMatrix() {
    // Shared prefix, both branches fail at the same point: furthest index, merged expected.
    row("or merges shared-prefix failures",
        new Or(Arrays.asList(
            new Seq(new Term('a'), new Term('b')),
            new Seq(new Term('a'), new Term('c')))),
        chars("ax"), false, 1, set("b", "c"));

    // Ordered choice takes the first matching branch; a trailing symbol then fails
    // the outer EOF anchor at the frontier of that first (shorter) branch.
    row("or first success wins and leaves the longer later branch unvisited",
        new Or(Arrays.asList(
            new Seq(new Term('a'), new Term('b')),
            new Seq(new Seq(new Term('a'), new Term('b')), new Term('c')))),
        chars("abc"), false, 2, set(EOF));
    // Both branches match the whole input, so the first branch succeeds outright;
    // swapping in an identical-length branch must keep this outcome (see permutation
    // property too).
    row("or equal-length branches succeed regardless of branch ordering",
        new Or(Arrays.asList(
            new Seq(new Term('a'), new Term('b')),
            new Seq(new Term('a'), new Term('b')))),
        chars("ab"), true, 2, null);

    // Nullable branch first: or picks epsilon and consumes zero.
    // Nullable branch first: or picks epsilon and consumes zero, so on non-empty
    // input the outer EOF anchor fails at the starting frontier; on empty input it
    // succeeds.
    row("or nullable first branch wins over a consuming alternative",
        new Or(Arrays.asList(new Empty(), new Term('a'))),
        chars("a"), false, 0, set(EOF));
    row("or nullable first branch accepts empty input",
        new Or(Arrays.asList(new Empty(), new Term('a'))),
        new char[0], true, 0, null);

    // Nullable branch last: the failing first branch backs out and epsilon wins,
    // consuming zero; here the remaining input is then rejected by the outer EOF.
    row("or failing branch backs out into nullable alternative",
        new Or(Arrays.asList(new Term('a'), new Empty())),
        chars("b"), false, 0, set("a", EOF));
    row("or failing branch on empty input backs out into nullable alternative",
        new Or(Arrays.asList(new Term('a'), new Empty())),
        new char[0], true, 0, null);

    // Longest selects the farther match over the ordering of branches.
    row("longest prefers the longer branch regardless of order",
        new Longest(Arrays.asList(
            new Seq(new Term('a'), new Term('b')),
            new Seq(new Seq(new Term('a'), new Term('b')), new Term('c')))),
        chars("abc"), true, 3, null);
    row("longest tie favors the first branch but frontier is identical",
        new Longest(Arrays.asList(new Term('a'), new Term('a'))),
        chars("a"), true, 1, null);

    // Atomic: a partially consumed failed branch is rewound; the outer ordered
    // choice can still start the second branch from the original frontier.
    row("atomic rewinds partial match before a later branch is tried",
        new Or(Arrays.asList(
            new Atomic(new Seq(new Term('a'), new Term('z'))),
            new Seq(new Term('a'), new Term('b')))),
        chars("ab"), true, 2, null);

    // Without atomic the outer or rewinds anyway, but the furthest-error bookkeeping
    // still reports inside the failed branch: multiple failure points are merged.
    row("non-atomic failure still contributes its furthest error",
        new Or(Arrays.asList(
            new Seq(new Term('a'), new Term('z')),
            new Seq(new Term('a'), new Term('b')))),
        chars("ax"), false, 1, set("z", "b"));

    // Lookahead success consumes nothing: a following single terminal consumes only
    // the first symbol and the leftover second symbol fails the outer EOF anchor.
    row("lookahead success rewinds the frontier",
        new Seq(new Peek(new Seq(new Term('a'), new Term('b'))), new Term('a')),
        chars("ab"), false, 1, set(EOF));
    // Lookahead over the whole input, followed by that same input, fully succeeds.
    row("lookahead guards a full match without consuming",
        new Seq(new Peek(new Seq(new Term('a'), new Term('b'))),
                new Seq(new Term('a'), new Term('b'))),
        chars("ab"), true, 2, null);

    // Lookahead failure propagates and leaves error context for the outer choice.
    row("lookahead failure is mergeable inside ordered choice",
        new Or(Arrays.asList(
            new Seq(new Peek(new Seq(new Term('a'), new Term('z'))), new Term('a')),
            new Term('b'))),
        chars("b"), true, 1, null);

    // Nested backtracking: failures at three points merge into one expected set.
    row("nested or merges three failure points",
        new Or(Arrays.asList(
            new Or(Arrays.asList(
                new Seq(new Term('a'), new Term('b')),
                new Seq(new Term('a'), new Term('c')))),
            new Seq(new Term('a'), new Term('d')))),
        chars("aq"), false, 1, set("b", "c", "d"));

    // Only the furthest error survives: an early error is discarded by a deeper one.
    row("deeper error supersedes an earlier expected entry",
        new Or(Arrays.asList(
            new Term('z'),
            new Seq(new Term('a'), new Term('z')))),
        chars("ab"), false, 1, set("z"));

    // Empty input: an EOF against a terminal yields index 0 with that terminal expected.
    row("empty input reports index zero",
        new Term('a'), new char[0], false, 0, set("a"));

    // Empty grammar + empty input succeeds and consumes nothing.
    row("epsilon over empty input",
        new Empty(), new char[0], true, 0, null);

    // Trailing input after a successful prefix fails at EOF with its physical index.
    row("trailing input reported at the leftover frontier",
        new Term('a'), chars("ab"), false, 1, set(EOF));
  }

  private static void row(
      String name, Node grammar, char[] input,
      boolean success, int index, Set<String> expected) {
    int[] symbols = new int[input.length];
    for (int i = 0; i < symbols.length; i++) symbols[i] = i;
    Outcome reference = Ref.interpret(grammar, input, symbols, input.length);
    Outcome actual = runChar(compileChar(grammar), new String(input));
    assertTrue(name + ": reference vs jparsec differ; reference=" + reference
        + " jparsec=" + actual + " grammar=" + render(grammar)
        + " input=" + Arrays.toString(input), equivalent(reference, actual));
    if (success) {
      assertTrue(name, actual.success);
      assertEquals(name, index, actual.consumed);
    } else {
      assertTrue(name, !actual.success);
      assertEquals(name, index, actual.errorIndex);
      assertEquals(name, expected, actual.expected);
    }
  }

  /**
   * Character-level and token-level errors must point at the same physical source
   * position when the tokenizer places tokens at known offsets, including inside a
   * shared-prefix ordered choice.
   */
  @Test(timeout = 30_000)
  public void tokenLevelAndCharacterLevelShareTheSourcePosition() {
    Node sharedPrefix = new Or(Arrays.asList(
        new Seq(new Term('a'), new Term('b')),
        new Seq(new Term('a'), new Term('c'))));
    char[] logical = chars("ax");

    // Token stream: " a x" -> the bad second token starts at physical index 2.
    List<Token> tokens = Arrays.asList(
        new Token(1, 1, Character.valueOf('a')),
        new Token(3, 1, Character.valueOf('x')));
    Outcome tokenOutcome = runTokens(compileToken(sharedPrefix), tokens);
    Outcome charOutcome = runChar(compileChar(sharedPrefix), "ax");

    assertTrue("token-level parse should fail", !tokenOutcome.success);
    // The bad second token carries physical index 3 in the (empty) outer source.
    assertEquals(3, tokenOutcome.errorIndex);
    assertEquals(set("b", "c"), tokenOutcome.expected);
    // same logical point, token-level offset differs but maps to the same token ordinal.
    assertEquals(1, charOutcome.errorIndex);
    assertEquals(charOutcome.expected, tokenOutcome.expected);
  }

  /**
   * The repetition guard: a parser that succeeds without consuming input must not
   * send {@code many} into an infinite loop. This is the empty-parser guard in
   * {@code RepeatAtLeastParser}/{@code SkipAtLeastParser}: once an iteration leaves
   * the physical frontier untouched the repetition terminates.
   */
  @Test(timeout = 3_000)
  public void manyTerminatesOnAnEmptySuccess() {
    // The empty success terminates the repetition immediately; the input is simply
    // left unconsumed (the outer EOF anchor reports it), rather than looping forever.
    runCharAndExpect(Parsers.always().many().<Void>cast(), "xyz", false, 0, set(EOF));
    // A nullable branch inside an ordered choice terminates instead of spinning.
    // A purely empty parser terminates on the first iteration at index 0.
    runCharAndExpect(Parsers.always().many().<Void>cast(),
        "aaaa", false, 0, set(EOF));
    // Many on a parser that consumes still iterates normally until it stops matching.
    assertEquals(3, Parsers.or(Scanners.isChar('a'), Parsers.always()).many()
        .parse("aaa").size());
    // And the guard accepts a genuine zero-match successful parse on empty input.
    assertEquals(Collections.emptyList(), Parsers.always().many().parse(""));
  }

  /**
   * Atomic rollback must be observable when the enclosing combinator does <em>not</em>
   * restore the frontier itself. {@link NonRestoring} runs a parser and, on failure,
   * keeps the advanced frontier exactly as the failed parser left it; wrapping it in
   * {@code atomic} is therefore the only thing that lets the following parser start
   * at the original position.
   */
  @Test(timeout = 30_000)
  public void atomicRollsBackThePhysicalFrontierForANonRestoringFollower() {
    final Parser<Void> prefixThenZ = Parsers.sequence(charTerminal('a'), charTerminal('z'));

    // A non-restoring attempt leaves the frontier wherever the failed parse stopped.
    // Without atomic that is index 1 (the shared "a" was consumed); with atomic the
    // failure rewinds it to index 0.
    assertEquals(1, (int) frontierAfter(prefixThenZ, "ax"));
    assertEquals(0, (int) frontierAfter(prefixThenZ.atomic(), "ax"));

    // Productive consequence: only the atomic failure lets a following "a" match the
    // leading symbol again and advance to index 1.
    assertEquals(1, (int) thenA(prefixThenZ.atomic(), "a"));
    Outcome stuck = runChar(runThenAParser(prefixThenZ).<Void>cast(), "a");
    assertTrue(!stuck.success);
    assertEquals(1, stuck.errorIndex);
    assertEquals(set("z", "a"), stuck.expected);
  }

  /** Runs {@code lead} ignoring its result and returns the frontier it leaves. */
  private static int frontierAfter(final Parser<Void> lead, String source) {
    Parser<Integer> probe = new Parser<Integer>() {
      @Override boolean apply(ParseContext ctxt) {
        lead.apply(ctxt);
        ctxt.result = ctxt.getIndex();
        return true;
      }
      @Override public String toString() { return "frontierProbe"; }
    };
    return applyChar(probe, source);
  }

  /** Runs {@code lead} ignoring its result and then matches a leading "a". */
  private static int thenA(Parser<Void> lead, String source) {
    return applyChar(runThenAParser(lead), source);
  }

  private static Parser<Integer> runThenAParser(final Parser<Void> lead) {
    return new Parser<Integer>() {
      @Override boolean apply(ParseContext ctxt) {
        lead.apply(ctxt);
        if (!charTerminal('a').apply(ctxt)) return false;
        ctxt.result = ctxt.getIndex();
        return true;
      }
      @Override public String toString() { return "thenA"; }
    };
  }

  private static <T> T applyChar(Parser<T> parser, String source) {
    ScannerState state = new ScannerState(source);
    if (!parser.apply(state)) {
      throw new AssertionError("probe parser unexpectedly failed");
    }
    return parser.getReturn(state);
  }

  private static void runCharAndExpect(
      Parser<Void> parser, String source, boolean success, int index, Set<String> expected) {
    Outcome outcome = runChar(parser, source);
    assertEquals(success, outcome.success);
    if (success) {
      assertEquals(index, outcome.consumed);
    } else {
      assertEquals(index, outcome.errorIndex);
      assertEquals(expected, outcome.expected);
    }
  }

  private static char[] chars(String s) {
    return s.toCharArray();
  }

  private static String blanks(int length) {
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) sb.append(' ');
    return sb.toString();
  }


  private static Set<String> set(String... values) {
    return new LinkedHashSet<String>(Arrays.asList(values));
  }

}
