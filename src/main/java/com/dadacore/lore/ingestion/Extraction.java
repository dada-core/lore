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
 */
public record Extraction(
        boolean scanned,
        String language,
        @Nullable Integer pageCount,
        boolean truncated,
        @Nullable String title,
        List<ExtractedChunk> chunks) {

    public Extraction {
        chunks = List.copyOf(chunks);
    }
}
