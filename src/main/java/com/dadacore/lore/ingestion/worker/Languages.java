package com.dadacore.lore.ingestion.worker;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dadacore.lore.ingestion.ExtractedChunk;

/**
 * French, English or another language, from the most common words of each language: enough to
 * choose how the text is indexed, without a dependency.
 */
final class Languages {

    static final String FRENCH = "fr";
    static final String ENGLISH = "en";
    static final String OTHER = "other";

    private static final int SAMPLE = 200_000;
    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    private static final Set<String> FRENCH_WORDS = Set.of(
            "le", "la", "les", "de", "des", "du", "un", "une", "et", "est", "en", "que", "qui", "dans",
            "pour", "pas", "sur", "au", "aux", "avec", "ce", "cette", "ces", "il", "elle", "ils", "sont",
            "par", "plus", "ne", "se", "sa", "son", "ses", "leur", "nous", "vous", "ou", "mais", "donc",
            "être", "avoir", "fait", "faire", "comme", "tout", "si", "puis", "lorsque", "avant", "après");
    private static final Set<String> ENGLISH_WORDS = Set.of(
            "the", "of", "and", "to", "in", "is", "that", "for", "it", "with", "as", "was", "on", "be",
            "by", "this", "are", "or", "from", "at", "an", "not", "have", "has", "but", "which", "you",
            "we", "they", "their", "will", "can", "if", "all", "then", "when", "before", "after", "into");

    private Languages() {
    }

    static String detect(List<ExtractedChunk> chunks) {
        StringBuilder sample = new StringBuilder();
        for (ExtractedChunk chunk : chunks) {
            if (sample.length() >= SAMPLE) {
                break;
            }
            sample.append(chunk.content()).append('\n');
        }
        return detect(sample.toString());
    }

    static String detect(String text) {
        int words = 0;
        int french = 0;
        int english = 0;
        Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            words++;
            if (FRENCH_WORDS.contains(word)) {
                french++;
            }
            if (ENGLISH_WORDS.contains(word)) {
                english++;
            }
        }
        int best = Math.max(french, english);
        // Too few common words, or too rare in the text: another language, or mostly code.
        if (best < 8 || best * 100 < words * 4) {
            return OTHER;
        }
        return french >= english ? FRENCH : ENGLISH;
    }
}
