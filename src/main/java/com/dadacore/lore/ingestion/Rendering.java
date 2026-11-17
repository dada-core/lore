package com.dadacore.lore.ingestion;

import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * A document laid out for reading: HTML made of a closed list of elements, one {@code section} per
 * place of the document ({@code h-7}, {@code part-3}), cut in parts that a browser shows at once.
 * Produced by the extraction process, cleaned again by Lore before it is kept.
 *
 * @param parts     the HTML of each part, from the first; a part ends between two sections, or two
 *                  blocks of a long section
 * @param places    every anchor of the rendering and the part that holds it, in the order of the document
 * @param truncated true when the rendering stops before the end of the document
 */
@JsonDeserialize(using = RenderingReader.class)
public record Rendering(List<String> parts, List<Place> places, boolean truncated) {

    /**
     * The number of the rules of rendering. A version of Lore that improves them raises it, and may
     * then extract again the versions rendered with older rules.
     */
    public static final int RULES = 1;

    /** A part holds about this many characters of HTML: a phone shows it in about a second. */
    public static final int PART_SIZE = 500_000;

    /** The HTML of a rendering never goes beyond: a document made of tiny cells would weigh ten times its text. */
    public static final int MAX_CHARACTERS = 30_000_000;

    /** The parts of a rendering at most: a part ends at a block, which may outgrow it. */
    public static final int MAX_PARTS = MAX_CHARACTERS / PART_SIZE * 4;

    /** The places of a rendering at most: beyond, the rendering stops, as at its maximum of characters. */
    public static final int MAX_PLACES = 100_000;

    /**
     * @param anchor {@code h-0} (before the first title), {@code h-7}, {@code part-3}
     * @param part   the part that holds it, from 1
     * @param level  the level of its title, 1 to 6; 0 for a place without title
     * @param title  its title, as plain text
     */
    public record Place(String anchor, int part, int level, @Nullable String title) {
    }

    /**
     * Refuses more parts or places than a rendering ever holds: a result written by a process that
     * a file took over could otherwise hold millions of them, built to exhaust the application.
     */
    public Rendering {
        if (parts.size() > MAX_PARTS || places.size() > MAX_PLACES) {
            throw new IllegalArgumentException("A rendering of " + parts.size() + " parts and " + places.size() + " places");
        }
        parts = List.copyOf(parts);
        places = List.copyOf(places);
    }
}
