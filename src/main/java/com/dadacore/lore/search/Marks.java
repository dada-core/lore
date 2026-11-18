package com.dadacore.lore.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A marked text turned into what the interface needs: the plain text, and the places of the words
 * found as {@code [start, end[} in UTF-16 units, the units of JavaScript. Two marks separated by a
 * single {@code _}, {@code -}, {@code .} or {@code /} become one: {@code pg_restore} is found as two
 * words by PostgreSQL, and shown as one.
 */
final class Marks {

    private static final String JOINERS = "_-./";

    private Marks() {
    }

    /** @param words the words found, as written in the text, in the order they appear */
    record Marked(String text, List<List<Integer>> highlights, List<String> words) {
    }

    static Marked parse(String marked) {
        StringBuilder text = new StringBuilder(marked.length());
        List<int[]> ranges = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < marked.length(); i++) {
            char c = marked.charAt(i);
            if (c == SearchIndex.MARK_START) {
                start = text.length();
            } else if (c == SearchIndex.MARK_END) {
                if (start >= 0 && text.length() > start) {
                    add(ranges, text, start, text.length());
                }
                start = -1;
            } else {
                text.append(c);
            }
        }
        List<List<Integer>> highlights = new ArrayList<>(ranges.size());
        Map<String, String> words = new LinkedHashMap<>();
        for (int[] range : ranges) {
            highlights.add(List.of(range[0], range[1]));
            String word = text.substring(range[0], range[1]);
            words.putIfAbsent(word.toLowerCase(Locale.ROOT), word);
        }
        return new Marked(text.toString(), List.copyOf(highlights), List.copyOf(words.values()));
    }

    private static void add(List<int[]> ranges, CharSequence text, int start, int end) {
        if (!ranges.isEmpty()) {
            int[] last = ranges.getLast();
            if (start == last[1] + 1 && JOINERS.indexOf(text.charAt(last[1])) >= 0) {
                last[1] = end;
                return;
            }
            if (start <= last[1]) {
                last[1] = Math.max(last[1], end);
                return;
            }
        }
        ranges.add(new int[] {start, end});
    }
}
