package com.dadacore.lore.ingestion.worker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dadacore.lore.ingestion.Rendering;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The rendering of a Word document, from the XHTML that Tika produces for its text (decision 4 of
 * the reader): titles, paragraphs, lists, tables, emphasis and links, nothing of the layout. Tika
 * writes the lists of Word as paragraphs that start with their number; they stay so. The titles are
 * numbered as {@link XhtmlText} numbers them, so that {@code h-7} is the same title in both.
 */
final class WordRendering extends DefaultHandler {

    private static final Set<String> HEADINGS = Set.of("h1", "h2", "h3", "h4", "h5", "h6");
    /** The elements that make a block of their own at the top of the document. */
    private static final Set<String> BLOCKS = Set.of("p", "pre", "table", "ul", "ol", "blockquote");
    /** The elements kept, and the name they are written with. */
    private static final Map<String, String> KEPT = Map.ofEntries(
            Map.entry("p", "p"), Map.entry("pre", "pre"), Map.entry("blockquote", "blockquote"),
            Map.entry("table", "table"), Map.entry("thead", "thead"), Map.entry("tbody", "tbody"),
            Map.entry("tr", "tr"), Map.entry("td", "td"), Map.entry("th", "th"),
            Map.entry("ul", "ul"), Map.entry("ol", "ol"), Map.entry("li", "li"),
            Map.entry("b", "strong"), Map.entry("strong", "strong"), Map.entry("i", "em"), Map.entry("em", "em"),
            Map.entry("u", "u"), Map.entry("s", "s"), Map.entry("strike", "s"), Map.entry("del", "s"),
            Map.entry("sup", "sup"), Map.entry("sub", "sub"));
    /** Elements that hold other elements only: text inside them would land outside the table. */
    private static final Set<String> STRUCTURE = Set.of("table", "thead", "tbody", "tr", "ul", "ol");
    private static final String HEADING = "\u0000heading";
    private static final Pattern BOOKMARK_LINK = Pattern.compile("<a href=\"#lore-bookmark:([^\"]*)\">");

    private final RenderingBuilder builder;
    /** Every element read, with the name written for it, or an empty name when it was not written. */
    private final Deque<String> elements = new ArrayDeque<>();
    /** The elements written in the current block and not closed yet. */
    private final Deque<String> open = new ArrayDeque<>();
    private final Map<String, String> bookmarks = new HashMap<>();
    private final List<String> headingBookmarks = new ArrayList<>();
    private final List<Rendering.Place> nested = new ArrayList<>();
    private StringBuilder block;
    private int blockDepth;
    private boolean implicitBlock;
    private int skipped;
    private int headingLevel;
    private boolean headingNested;
    private final StringBuilder headingHtml = new StringBuilder();
    private final StringBuilder headingText = new StringBuilder();
    private int titles;
    private String currentAnchor = "h-0";
    private int pre;

    WordRendering() {
        this(new RenderingBuilder());
    }

    WordRendering(RenderingBuilder builder) {
        this.builder = builder;
    }

    /**
     * Set when the layout of the document failed: the rendering is then left out, and the text,
     * read by the other handler of the same reading, is kept.
     */
    private boolean broken;

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
        if (broken) {
            return;
        }
        try {
            start(localName, qName, attributes);
        } catch (RuntimeException e) {
            broken = true;
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        if (broken) {
            return;
        }
        try {
            end();
        } catch (RuntimeException e) {
            broken = true;
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        if (broken) {
            return;
        }
        try {
            text(ch, start, length);
        } catch (RuntimeException e) {
            broken = true;
        }
    }

    private void start(String localName, String qName, Attributes attributes) {
        String name = name(localName, qName);
        if (skipped > 0 || name.equals("head")) {
            skipped++;
            elements.push("");
            return;
        }
        if (name.equals("a")) {
            link(attributes);
            return;
        }
        if (HEADINGS.contains(name) && headingLevel == 0) {
            headingNested = block != null && !implicitBlock;
            if (implicitBlock) {
                flushBlock();
            }
            headingLevel = name.charAt(1) - '0';
            headingHtml.setLength(0);
            headingText.setLength(0);
            elements.push(HEADING);
            return;
        }
        if (name.equals("br")) {
            write("<br>");
            elements.push("");
            return;
        }
        if (name.equals("img")) {
            String alt = attributes.getValue("alt");
            write("<span class=\"lore-image\">" + RenderingBuilder.escape(alt == null ? "" : alt.strip()) + "</span>");
            elements.push("");
            return;
        }
        String kept = KEPT.get(name);
        if (kept == null) {
            // div, span, body and the rest: their content only.
            elements.push("");
            return;
        }
        if (headingLevel == 0 && BLOCKS.contains(name) && (block == null || implicitBlock)) {
            if (implicitBlock) {
                flushBlock();
            }
            block = new StringBuilder();
            blockDepth = elements.size() + 1;
        }
        write("<" + kept + ">");
        if (headingLevel == 0) {
            open.push(kept);
        }
        if (kept.equals("pre")) {
            pre++;
        }
        elements.push(kept);
    }

    private void end() {
        String written = elements.isEmpty() ? "" : elements.pop();
        if (skipped > 0) {
            skipped--;
            return;
        }
        if (written.equals(HEADING)) {
            endHeading();
            return;
        }
        if (written.isEmpty()) {
            return;
        }
        write("</" + written + ">");
        if (headingLevel == 0 && !open.isEmpty()) {
            open.pop();
        }
        if (written.equals("pre")) {
            pre--;
        }
        if (block != null && !implicitBlock && elements.size() < blockDepth) {
            flushBlock();
        }
    }

    private void text(char[] ch, int start, int length) {
        if (skipped > 0 || length == 0) {
            return;
        }
        String text = new String(ch, start, length);
        if (headingLevel > 0) {
            // As XhtmlText keeps it, so that an empty title is empty for both.
            headingText.append(text, 0, Math.min(length, Math.max(0, XhtmlText.MAX_HEADING * 2 - headingText.length())));
            headingHtml.append(RenderingBuilder.escape(text.replace('\n', ' ')));
            return;
        }
        if (block == null || (!open.isEmpty() && STRUCTURE.contains(open.peek()))) {
            if (text.isBlank()) {
                return;
            }
            if (block != null) {
                // Text right inside a table or a list would be moved out of it by the browser.
                return;
            }
            block = new StringBuilder("<p>");
            open.push("p");
            implicitBlock = true;
        }
        String escaped = RenderingBuilder.escape(text);
        block.append(pre > 0 ? escaped : escaped.replace("\n", "<br>"));
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) {
        // Whitespace between elements, which the layout does not need.
    }

    /** The rendering, once the document is read or its reading stopped at the maximum; null when it failed. */
    @org.jspecify.annotations.Nullable Rendering build(boolean textTruncated) {
        if (broken) {
            return null;
        }
        if (headingLevel > 0) {
            endHeading();
        }
        flushBlock();
        Rendering rendering = builder.build(textTruncated);
        List<String> parts = new ArrayList<>();
        for (String part : rendering.parts()) {
            parts.add(resolveBookmarks(part));
        }
        return new Rendering(parts, rendering.places(), rendering.truncated());
    }

    private void link(Attributes attributes) {
        String type = attributes.getValue("class");
        if (type != null && type.startsWith("external-ref")) {
            // Tika lists the targets of the links again at the end of the document.
            skipped++;
            elements.push("");
            return;
        }
        String href = attributes.getValue("href");
        String bookmark = attributes.getValue("name");
        if (href == null && bookmark != null) {
            String key = RenderingBuilder.escape(bookmark);
            if (headingLevel > 0) {
                headingBookmarks.add(key);
            } else {
                bookmarks.putIfAbsent(key, currentAnchor);
            }
            elements.push("");
            return;
        }
        if (href != null && MarkdownRendering.external(href)) {
            write(MarkdownRendering.externalLink(href));
            if (headingLevel == 0) {
                open.push("a");
            }
            elements.push("a");
        } else if (href != null && href.startsWith("#") && href.length() > 1) {
            write("<a href=\"#lore-bookmark:" + RenderingBuilder.escape(href.substring(1)) + "\">");
            if (headingLevel == 0) {
                open.push("a");
            }
            elements.push("a");
        } else {
            // Another file, or a scheme other than the web: its text only.
            elements.push("");
        }
    }

    private void endHeading() {
        String title = XhtmlText.heading(headingText.toString());
        int level = headingLevel;
        headingLevel = 0;
        String anchor = currentAnchor;
        if (!title.isEmpty()) {
            titles++;
            anchor = "h-" + titles;
            if (headingNested) {
                String tag = "h" + RenderingBuilder.heading(level);
                block.append('<').append(tag).append(" id=\"").append(anchor).append("\">").append(headingHtml)
                        .append("</").append(tag).append('>');
                nested.add(new Rendering.Place(anchor, 0, level, title));
            } else {
                builder.section(anchor, level, title, headingHtml.toString());
                currentAnchor = anchor;
            }
        }
        for (String bookmark : headingBookmarks) {
            bookmarks.putIfAbsent(bookmark, anchor);
        }
        headingBookmarks.clear();
    }

    private void write(String html) {
        if (headingLevel > 0) {
            headingHtml.append(html);
            return;
        }
        if (block == null) {
            block = new StringBuilder("<p>");
            open.push("p");
            implicitBlock = true;
        }
        block.append(html);
    }

    private void flushBlock() {
        if (block == null) {
            return;
        }
        while (!open.isEmpty()) {
            block.append("</").append(open.pop()).append('>');
        }
        String html = block.toString();
        block = null;
        implicitBlock = false;
        pre = 0;
        // Word documents hold many empty paragraphs, which would only add blank space.
        if (!html.replaceAll("<[^>]*>", "").isBlank() || html.contains("lore-image")) {
            builder.block(html, List.copyOf(nested));
        }
        nested.clear();
    }

    private String resolveBookmarks(String part) {
        Matcher link = BOOKMARK_LINK.matcher(part);
        StringBuilder out = new StringBuilder(part.length());
        while (link.find()) {
            String anchor = bookmarks.get(link.group(1));
            // A link to a bookmark the document does not have keeps its text, without the link.
            link.appendReplacement(out, Matcher.quoteReplacement(anchor == null ? "<a>" : "<a href=\"#" + anchor + "\">"));
        }
        link.appendTail(out);
        return out.toString();
    }

    private static String name(String localName, String qName) {
        return (localName == null || localName.isEmpty() ? qName : localName).toLowerCase(Locale.ROOT);
    }
}
