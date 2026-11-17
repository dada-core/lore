package com.dadacore.lore.ingestion.jobs;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.dadacore.lore.ingestion.Rendering;
import com.dadacore.lore.shared.Texts;
import org.jspecify.annotations.Nullable;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

/**
 * Cleans what the extraction process returns as a rendering before Lore keeps it: that process
 * reads hostile files, and a file that took control of it could return anything. OWASP Java HTML
 * Sanitizer keeps the closed list of elements of the reader and no other attribute than a link to
 * the web, an anchor of Lore and the classes of Lore. The interface forbids any script besides
 * ({@code script-src 'self'}).
 */
final class RenderingPolicy {

    /** The elements of a rendering, decision 6 of the reader. */
    static final List<String> ELEMENTS = List.of("section", "h1", "h2", "h3", "h4", "h5", "h6", "p", "br", "hr",
            "ul", "ol", "li", "blockquote", "pre", "code", "table", "thead", "tbody", "tr", "th", "td", "strong",
            "b", "em", "i", "u", "s", "del", "kbd", "sup", "sub", "details", "summary", "a", "span");

    /** The anchors of Lore only: a document cannot take the identifier of an element of the interface. */
    static final Pattern ANCHOR = Pattern.compile("(?:h|part)-\\d{1,7}");
    private static final Pattern HREF = Pattern.compile("(?i)(?:https?:|mailto:)\\S+|#(?:h|part)-\\d{1,7}");
    private static final Pattern CLASS = Pattern.compile("lore-(?:image|continued)");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements(ELEMENTS.toArray(String[]::new))
            .allowAttributes("id").matching(ANCHOR).onElements("section", "h1", "h2", "h3", "h4", "h5", "h6")
            .allowAttributes("class").matching(CLASS).onElements("section", "span", "a")
            .allowAttributes("href").matching(HREF).onElements("a")
            .allowUrlProtocols("http", "https", "mailto")
            .allowAttributes("target").matching(Pattern.compile("_blank")).onElements("a")
            .requireRelsOnLinks("noopener", "noreferrer", "nofollow")
            .allowAttributes("start").matching(Pattern.compile("\\d{1,6}")).onElements("ol")
            .toFactory();

    private RenderingPolicy() {
    }

    /** The rendering cleaned, or null when there is none worth keeping. */
    static @Nullable Rendering clean(@Nullable Rendering rendering) {
        if (rendering == null || rendering.parts().isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        int total = 0;
        boolean truncated = rendering.truncated();
        for (String part : rendering.parts()) {
            if (part == null) {
                continue;
            }
            // PostgreSQL refuses the character NUL in a text, and no document needs a control character.
            String cleaned = POLICY.sanitize(CONTROL.matcher(part).replaceAll(""));
            if (total + cleaned.length() > Rendering.MAX_CHARACTERS) {
                truncated = true;
                break;
            }
            total += cleaned.length();
            parts.add(cleaned);
        }
        if (parts.isEmpty()) {
            return null;
        }
        List<Rendering.Place> places = new ArrayList<>();
        for (Rendering.Place place : rendering.places()) {
            if (places.size() >= Rendering.MAX_PLACES) {
                break;
            }
            if (place == null || place.anchor() == null || !ANCHOR.matcher(place.anchor()).matches()
                    || place.part() < 1 || place.part() > parts.size() || place.level() < 0 || place.level() > 6) {
                continue;
            }
            String title = place.title() == null ? null : Texts.cut(place.title().replaceAll("\\p{Cc}", " ").strip(), 200);
            places.add(new Rendering.Place(place.anchor(), place.part(), place.level(),
                    title == null || title.isEmpty() ? null : title));
        }
        return new Rendering(parts, places, truncated);
    }
}
