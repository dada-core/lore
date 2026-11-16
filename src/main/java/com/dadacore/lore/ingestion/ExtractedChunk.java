package com.dadacore.lore.ingestion;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A passage of a document, small enough to be found and shown on its own.
 *
 * @param ordinal  its position in the document, from 0
 * @param anchor   a stable reference to the place in the document: {@code page-12} for a page of a
 *                 PDF, {@code h-7} for the part under the seventh title, {@code part-3} for a text
 *                 file; a long place is cut in several chunks, {@code page-12-2} and so on
 * @param page     the page of a PDF, from 1
 * @param headings the titles above the passage, from the highest level
 */
public record ExtractedChunk(int ordinal, String anchor, @Nullable Integer page, List<String> headings, String content) {

    public ExtractedChunk {
        headings = List.copyOf(headings);
    }
}
