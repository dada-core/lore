package com.dadacore.lore.ingestion.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.dadacore.lore.ingestion.Rendering;
import org.jspecify.annotations.Nullable;

/**
 * Writes a rendering: one {@code <section id="...">} per place of the document, each made of
 * blocks, and a new part once a part holds {@value Rendering#PART_SIZE} characters. A part ends
 * between two sections or two blocks; a section cut in two goes on in a {@code section} without
 * identifier, of class {@code lore-continued}.
 */
final class RenderingBuilder {

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");

    private final int partSize;
    private final int maxCharacters;
    private final List<String> parts = new ArrayList<>();
    private final List<Rendering.Place> places = new ArrayList<>();
    private final StringBuilder part = new StringBuilder();
    private boolean sectionOpen;
    /** True once the current section holds a block, so that it may be cut there. */
    private boolean sectionHasBlock;
    private int written;
    private boolean truncated;

    RenderingBuilder() {
        this(Rendering.PART_SIZE, Rendering.MAX_CHARACTERS);
    }

    RenderingBuilder(int partSize, int maxCharacters) {
        this.partSize = partSize;
        this.maxCharacters = maxCharacters;
    }

    /**
     * Opens the section of a place.
     *
     * @param level        the level of its title, 1 to 6, or 0 without title
     * @param title        the title as plain text, for the outline
     * @param headingHtml  the content of the title, already escaped; null to write the plain title
     */
    void section(String anchor, int level, @Nullable String title, @Nullable String headingHtml) {
        if (truncated) {
            return;
        }
        StringBuilder html = new StringBuilder("<section id=\"").append(anchor).append("\">");
        if (level > 0) {
            String tag = "h" + heading(level);
            html.append('<').append(tag).append('>')
                    .append(headingHtml != null ? headingHtml : escape(title == null ? "" : title))
                    .append("</").append(tag).append('>');
        }
        if (!fits(html.length()) || places.size() >= Rendering.MAX_PLACES) {
            truncated = true;
            return;
        }
        closeSection();
        if (part.length() >= partSize) {
            nextPart();
        }
        places.add(new Rendering.Place(anchor, parts.size() + 1, level, title));
        append(html.toString());
        sectionOpen = true;
        sectionHasBlock = false;
    }

    /** A block of the current section, opening the part before the first title when none is open. */
    void block(String html) {
        block(html, List.of());
    }

    /**
     * A block that holds titles of its own (a title inside a quote or a table): their places are
     * those of the part the block lands in.
     */
    void block(String html, List<Rendering.Place> nested) {
        if (truncated || html.isEmpty()) {
            return;
        }
        if (!sectionOpen) {
            section("h-0", 0, null, null);
        }
        if (truncated || !fits(html.length()) || places.size() + nested.size() > Rendering.MAX_PLACES) {
            truncated = true;
            return;
        }
        if (sectionHasBlock && part.length() + html.length() > partSize) {
            part.append("</section>");
            nextPart();
            part.append("<section class=\"lore-continued\">");
        }
        for (Rendering.Place place : nested) {
            places.add(new Rendering.Place(place.anchor(), parts.size() + 1, place.level(), place.title()));
        }
        append(html);
        sectionHasBlock = true;
    }

    /** The rendering, ended; {@code textTruncated} when the text read was cut before the end of the file. */
    Rendering build(boolean textTruncated) {
        closeSection();
        if (!part.isEmpty()) {
            parts.add(part.toString());
            part.setLength(0);
        }
        return new Rendering(parts, places, truncated || textTruncated);
    }

    boolean truncated() {
        return truncated;
    }

    /** The parts written so far, for the rewriting of links once the document is read. */
    List<String> parts() {
        return parts;
    }

    private boolean fits(int length) {
        return written + length <= maxCharacters;
    }

    private void append(String html) {
        written += html.length();
        part.append(html);
    }

    private void closeSection() {
        if (sectionOpen) {
            part.append("</section>");
            sectionOpen = false;
        }
    }

    private void nextPart() {
        parts.add(part.toString());
        part.setLength(0);
    }

    /** The level of a title in the page, under the title of the document. */
    static int heading(int level) {
        return Math.min(level + 1, 6);
    }

    /** Text made safe inside an element or an attribute, control characters removed. */
    static String escape(String text) {
        String clean = CONTROL.matcher(text).replaceAll("");
        StringBuilder out = new StringBuilder(clean.length() + 16);
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&#34;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
