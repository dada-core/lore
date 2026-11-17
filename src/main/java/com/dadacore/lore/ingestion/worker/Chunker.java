package com.dadacore.lore.ingestion.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.dadacore.lore.ingestion.ExtractedChunk;
import com.dadacore.lore.shared.Texts;
import org.jspecify.annotations.Nullable;

/**
 * Cuts the text of a document into chunks of {@value #MAX_CHUNK} characters at most, between two
 * paragraphs when possible, and stops at the maximum kept for a document.
 */
final class Chunker {

    static final int MAX_CHUNK = 4000;

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\u00A0]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n\\s*\\n+");

    private final int maxCharacters;
    private final List<ExtractedChunk> chunks = new ArrayList<>();
    private int characters;
    private boolean truncated;

    Chunker(int maxCharacters) {
        this.maxCharacters = maxCharacters;
    }

    /**
     * A place of the document (a page, the part under a title): its paragraphs become one chunk,
     * or several when they are too long, under the anchors {@code anchor}, {@code anchor-2}...
     */
    void section(String anchor, @Nullable Integer page, List<String> headings, List<String> paragraphs) {
        StringBuilder current = new StringBuilder();
        int part = 1;
        for (String paragraph : paragraphs) {
            String text = clean(paragraph);
            for (String piece : pieces(text)) {
                if (!current.isEmpty() && current.length() + 2 + piece.length() > MAX_CHUNK) {
                    add(part == 1 ? anchor : anchor + "-" + part, page, headings, current.toString());
                    part++;
                    current.setLength(0);
                }
                if (!current.isEmpty()) {
                    current.append("\n\n");
                }
                current.append(piece);
            }
        }
        if (!current.isEmpty()) {
            add(part == 1 ? anchor : anchor + "-" + part, page, headings, current.toString());
        }
    }

    /** True once the maximum is reached: the rest of the document is left out. */
    boolean full() {
        return truncated;
    }

    /** Called when the reading of a file stopped at the maximum, before the chunks were made. */
    void markTruncated() {
        truncated = true;
    }

    int characters() {
        return characters;
    }

    boolean truncated() {
        return truncated;
    }

    List<ExtractedChunk> chunks() {
        return List.copyOf(chunks);
    }

    private void add(String anchor, @Nullable Integer page, List<String> headings, String content) {
        if (truncated) {
            return;
        }
        String kept = content;
        if (characters + kept.length() > maxCharacters) {
            kept = Texts.cut(kept, Math.max(0, maxCharacters - characters));
            truncated = true;
        }
        if (kept.isBlank()) {
            return;
        }
        characters += kept.length();
        chunks.add(new ExtractedChunk(chunks.size(), anchor, page, headings, kept));
    }

    /** Spaces gathered, control characters removed, lines kept. */
    static String clean(String text) {
        String withoutControls = CONTROL.matcher(text.replace("\r\n", "\n").replace('\r', '\n')).replaceAll("");
        String lines = SPACES.matcher(withoutControls).replaceAll(" ");
        return BLANK_LINES.matcher(lines).replaceAll("\n\n").strip();
    }

    /** A paragraph longer than a chunk, cut at a space when there is one. */
    private static List<String> pieces(String text) {
        List<String> pieces = new ArrayList<>();
        String rest = text;
        while (rest.length() > MAX_CHUNK) {
            int cut = rest.lastIndexOf(' ', MAX_CHUNK);
            if (cut < MAX_CHUNK / 2) {
                cut = Texts.end(rest, MAX_CHUNK);
            }
            pieces.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty()) {
            pieces.add(rest);
        }
        return pieces;
    }
}
