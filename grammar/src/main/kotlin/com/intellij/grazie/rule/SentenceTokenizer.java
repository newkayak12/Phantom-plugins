// Copyright 2026 Phantom contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.grazie.rule;

import com.intellij.grazie.text.TextContent;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Splits an extracted text into sentences. A sentence ends after '.', '!', '?' or '…' (and the closing quotes or
 * brackets right after) that are followed by whitespace, unless the period ends a common abbreviation or an initial;
 * a blank line also ends one. Each sentence runs up to where the next starts, so it keeps its trailing whitespace.
 */
public final class SentenceTokenizer {
  private static final Key<List<Sentence>> TOKENIZED = Key.create("grammar sentence tokenization");

  private static final Set<String> ABBREVIATIONS = Set.of(
    "e.g", "i.e", "etc", "vs", "cf", "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "no", "fig", "approx", "incl",
    "z.b", "d.h", "bzw", "usw", "ca", "vgl", "p.s"
  );

  public static List<Sentence> tokenize(@NotNull TextContent content) {
    List<Sentence> result = content.getUserData(TOKENIZED);
    if (result == null) {
      result = split(content);
      content.putUserData(TOKENIZED, result);
    }
    return result;
  }

  private static List<Sentence> split(TextContent content) {
    String text = content.toString();
    List<Sentence> result = new ArrayList<>();
    int start = 0;
    int i = 0;
    while (i < text.length()) {
      ProgressManager.checkCanceled();
      int end = sentenceEnd(text, i);
      if (end > 0) {
        int next = end;
        while (next < text.length() && Character.isWhitespace(text.charAt(next))) next++;
        if (next > start && hasLetterOrDigit(text, start, next)) {
          result.add(sentence(content, text, start, next));
          start = next;
        }
        i = Math.max(next, i + 1);
      }
      else {
        i++;
      }
    }
    if (start < text.length() && hasLetterOrDigit(text, start, text.length())) {
      result.add(sentence(content, text, start, text.length()));
    }
    else if (!result.isEmpty() && start < text.length()) {
      Sentence last = result.removeLast();
      result.add(sentence(content, text, last.start(), text.length()));
    }
    return List.copyOf(result);
  }

  /** @return the offset after the sentence terminator at {@code i} (and its closing punctuation), or -1 */
  private static int sentenceEnd(String text, int i) {
    char c = text.charAt(i);
    if (c == '\n') {
      int j = i + 1;
      while (j < text.length() && text.charAt(j) != '\n' && Character.isWhitespace(text.charAt(j))) j++;
      return j < text.length() && text.charAt(j) == '\n' ? i : -1;
    }
    if (c != '.' && c != '!' && c != '?' && c != '…') return -1;

    int end = i + 1;
    while (end < text.length() && ".!?…".indexOf(text.charAt(end)) >= 0) end++;
    while (end < text.length() && "\"'”’)]»".indexOf(text.charAt(end)) >= 0) end++;
    if (end < text.length() && !Character.isWhitespace(text.charAt(end))) return -1;
    if (c == '.' && end == i + 1 && isAbbreviation(text, i)) return -1;
    return end;
  }

  private static boolean isAbbreviation(String text, int dot) {
    int start = dot;
    while (start > 0 && (Character.isLetter(text.charAt(start - 1)) || text.charAt(start - 1) == '.')) start--;
    String word = text.substring(start, dot);
    if (word.isEmpty()) return false;
    if (word.length() == 1 && Character.isUpperCase(word.charAt(0))) return true; // an initial: "J. Smith"
    return ABBREVIATIONS.contains(word.toLowerCase(Locale.ROOT));
  }

  private static boolean hasLetterOrDigit(String text, int start, int end) {
    for (int i = start; i < end; i++) {
      if (Character.isLetterOrDigit(text.charAt(i))) return true;
    }
    return false;
  }

  private static Sentence sentence(TextContent content, String text, int start, int end) {
    List<Exclusion> exclusions = new ArrayList<>();
    for (int offset : content.markupOffsets()) {
      if (offset >= start && offset < end) exclusions.add(new Exclusion(offset - start, Exclusion.Kind.Markup));
    }
    for (int offset : content.unknownOffsets()) {
      if (offset >= start && offset < end) exclusions.add(new Exclusion(offset - start, Exclusion.Kind.Unknown));
    }
    exclusions.sort((a, b) -> Integer.compare(a.offset(), b.offset()));
    return new Sentence(start, text.substring(start, end), List.copyOf(exclusions));
  }

  /** A place in a sentence where the extracted text dropped markup or an unknown fragment (code, a placeholder) */
  public record Exclusion(int offset, Kind kind) {
    public enum Kind { Markup, Unknown }
  }

  public record Sentence(int start, String text, List<Exclusion> exclusions) {
    public int end() {
      return start + text.length();
    }

    public TextRange range() {
      return new TextRange(start, end());
    }
  }
}
