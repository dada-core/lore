package com.dadacore.lore.ingestion.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.dadacore.lore.shared.Texts;
import org.apache.tika.exception.WriteLimitReachedException;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The text of the XHTML that Tika produces, gathered by place: by page for a PDF
 * ({@code <div class="page">}), by title for a Word document ({@code <h1>} to {@code <h6>}).
 *
 * <p>It stops the reading once {@code maxCharacters} are collected, so that a huge document does
 * not fill the memory before being cut.
 */
final class XhtmlText extends DefaultHandler {

    /** A place of the document and its paragraphs. */
    record Place(int number, List<String> headings, List<String> paragraphs) {
    }

    /** Thrown to stop Tika once enough text is collected. */
    /**
     * Thrown once the maximum is reached. A write limit of Tika, so that its parsers stop there
     * instead of keeping it as one more failure of a page, as the PDF parser does.
     */
    static final class Enough extends WriteLimitReachedException {
        Enough(int maxCharacters) {
            super(maxCharacters);
        }
    }

    private static final Set<String> BLOCKS = Set.of("p", "li", "td", "th", "pre", "blockquote", "dt", "dd", "tr", "div");
    private static final Set<String> HEADINGS = Set.of("h1", "h2", "h3", "h4", "h5", "h6");

    /** A title is kept to this length: it is repeated in each chunk below it. */
    static final int MAX_HEADING = 200;

    private final boolean byPage;
    private final int maxCharacters;
    private final List<Place> places = new ArrayList<>();
    private final List<String> path = new ArrayList<>();
    private final StringBuilder paragraph = new StringBuilder();
    private final StringBuilder heading = new StringBuilder();
    private Place current;
    private int headingLevel;
    private int titles;
    private int collected;
    private boolean stopped;
    /** Inside {@code <head>}: the properties of the file (its title), not its text. */
    private int head;

    private XhtmlText(boolean byPage, int maxCharacters) {
        this.byPage = byPage;
        this.maxCharacters = maxCharacters;
        if (!byPage) {
            // The part before the first title.
            current = new Place(0, List.of(), new ArrayList<>());
            places.add(current);
        }
    }

    static XhtmlText byPage(int maxCharacters) {
        return new XhtmlText(true, maxCharacters);
    }

    static XhtmlText byHeading(int maxCharacters) {
        return new XhtmlText(false, maxCharacters);
    }

    List<Place> places() {
        flush();
        return places;
    }

    /** True when the reading was stopped at the maximum. */
    boolean stopped() {
        return stopped;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
        String name = name(localName, qName);
        if (head > 0 || name.equals("head")) {
            head++;
            return;
        }
        if (byPage && name.equals("div") && "page".equals(attributes.getValue("class"))) {
            flush();
            current = new Place(places.size() + 1, List.of(), new ArrayList<>());
            places.add(current);
        } else if (!byPage && HEADINGS.contains(name)) {
            flush();
            headingLevel = name.charAt(1) - '0';
            heading.setLength(0);
        } else if (BLOCKS.contains(name)) {
            flush();
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        if (head > 0) {
            head--;
            return;
        }
        String name = name(localName, qName);
        if (!byPage && headingLevel > 0 && HEADINGS.contains(name)) {
            String title = heading(heading.toString());
            int level = Math.min(headingLevel, path.size() + 1);
            headingLevel = 0;
            // An empty heading (a blank paragraph styled as a title) changes nothing.
            if (!title.isEmpty()) {
                while (path.size() >= level) {
                    path.removeLast();
                }
                path.add(title);
                titles++;
                current = new Place(titles, List.copyOf(path), new ArrayList<>());
                places.add(current);
            }
        } else if (name.equals("br")) {
            paragraph.append('\n');
        } else if (BLOCKS.contains(name)) {
            flush();
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        if (head > 0) {
            return;
        }
        if (headingLevel > 0) {
            // Counted too, so that a huge title stops the reading like a huge paragraph.
            heading.append(ch, start, Math.min(length, Math.max(0, MAX_HEADING * 2 - heading.length())));
            collected += length;
            if (collected > maxCharacters) {
                stopped = true;
                throw new Enough(maxCharacters);
            }
            return;
        }
        if (current == null) {
            return;
        }
        paragraph.append(ch, start, length);
        collected += length;
        if (collected > maxCharacters) {
            stopped = true;
            flush();
            throw new Enough(maxCharacters);
        }
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) {
        if (current != null && headingLevel == 0) {
            paragraph.append(ch, start, length);
        }
    }

    private void flush() {
        if (current != null && !paragraph.toString().isBlank()) {
            current.paragraphs().add(paragraph.toString());
        }
        paragraph.setLength(0);
    }

    /** A title, cleaned and cut to {@value #MAX_HEADING} characters. */
    static String heading(String text) {
        String title = Chunker.clean(text).replace('\n', ' ');
        return Texts.cut(title, MAX_HEADING).strip();
    }

    private static String name(String localName, String qName) {
        return (localName == null || localName.isEmpty() ? qName : localName).toLowerCase(java.util.Locale.ROOT);
    }
}
