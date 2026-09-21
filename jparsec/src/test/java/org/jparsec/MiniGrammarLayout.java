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
import java.util.List;

/**
 * Renders a logical symbol sequence (the packed string a char-level parser sees)
 * into physical source text, together with the {@link Token} array a token-level
 * parser sees, preserving each token's physical start index and length.
 *
 * <p>Having several layouts is what proves that the token level and the
 * character level agree on the same source position even when token widths and
 * inter-token gaps differ from the packed logical positions.
 */
enum MiniGrammarLayout {

  /** Logical characters are the physical characters; token i starts at i. */
  PACKED {
    @Override String render(String logical) { return logical; }
    @Override int width(char symbol, int symbolIndex) { return 1; }
  },

  /** Single-space gap between symbols; gaps must not shift the error index. */
  SPACED {
    @Override String render(String logical) {
      StringBuilder builder = new StringBuilder();
      for (int i = 0; i < logical.length(); i++) {
        if (i > 0) builder.append(' ');
        builder.append(logical.charAt(i));
      }
      return builder.toString();
    }
    @Override int width(char symbol, int symbolIndex) { return 1; }
  },

  /**
   * The {@code d} symbol renders as the two-character lexeme "xy", exercising
   * non-uniform token widths: logical position no longer equals physical index.
   */
  WIDE {
    @Override String render(String logical) {
      StringBuilder builder = new StringBuilder();
      for (int i = 0; i < logical.length(); i++) {
        builder.append(spelling(logical.charAt(i)));
      }
      return builder.toString();
    }
    @Override int width(char symbol, int symbolIndex) {
      return spelling(symbol).length();
    }
  };

  abstract String render(String logical);
  abstract int width(char symbol, int symbolIndex);

  static String spelling(char symbol) {
    return symbol == 'd' ? "xy" : String.valueOf(symbol);
  }

  /** Physical 0-based index of the logical symbol at {@code symbolIndex}. */
  int physicalIndex(String logical, int symbolIndex) {
    int index = 0;
    for (int i = 0; i < symbolIndex && i < logical.length(); i++) {
      index += width(logical.charAt(i), i);
    }
    return index;
  }

  /** Builds the token array corresponding to a packed logical string. */
  List<Token> tokens(String logical) {
    List<Token> tokens = new ArrayList<>();
    int index = 0;
    for (int i = 0; i < logical.length(); i++) {
      char symbol = logical.charAt(i);
      int width = width(symbol, i);
      tokens.add(new Token(index, width, String.valueOf(symbol)));
      index += width;
    }
    return tokens;
  }
}
