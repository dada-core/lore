package com.dadacore.lore.ingestion;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The text extracted from a file, cut into chunks.
 *
 * @param scanned      a PDF where at least half of the pages have no text: an image of a document
 * @param language     {@code fr}, {@code en} or {@code other}
 * @param pageCount    the number of pages of a PDF; null for the other formats
 * @param truncated    true when the text went beyond the maximum kept, which was cut
 * @param title        the first title of level 1 of a Markdown file, if any
 * @param rendering    the document laid out for reading; null for a PDF, which the reader shows as it is
 */
public record Extraction(
        boolean scanned,
        String language,
        @Nullable Integer pageCount,
        boolean truncated,
        @Nullable String title,
        List<ExtractedChunk> chunks,
        @Nullable Rendering rendering) {

    /** The result of an extraction never weighs more: 10 million characters, titles, rendering and JSON included. */
    public static final long MAX_RESULT_BYTES = 100L * 1024 * 1024;

    public Extraction {
        chunks = List.copyOf(chunks);
    }

    /** The same extraction with another rendering: the one Lore cleaned, or none. */
    public Extraction withRendering(@Nullable Rendering cleaned) {
        return new Extraction(scanned, language, pageCount, truncated, title, chunks, cleaned);
    }
}
