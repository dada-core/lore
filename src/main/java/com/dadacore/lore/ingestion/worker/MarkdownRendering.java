package com.dadacore.lore.ingestion.worker;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dadacore.lore.ingestion.Rendering;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Document;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;

/**
 * The rendering of a Markdown file (decision 3 of the reader): tables, struck text and bare links;
 * code blocks as they are written; a short list of HTML elements; links to the web only, a link to
 * a title of the document leading to its section; images never loaded.
 */
final class MarkdownRendering {

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create(),
            StrikethroughExtension.create(), AutolinkExtension.create());

    /** The parser of the chunks and of the rendering: the same tree, the same titles. */
    static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

    /** The HTML written in a Markdown file that is kept: common in README files, harmless. */
    private static final Set<String> KEPT_HTML = Set.of("br", "kbd", "sup", "sub", "details", "summary");
    /** A comment, or a tag with its attributes. */
    private static final Pattern TAG = Pattern.compile("<!--.*?(?:-->|$)|<(/?)([a-zA-Z][a-zA-Z0-9-]*)\\b[^>]*>",
            Pattern.DOTALL);
    /** Elements whose content is not text to show: left out with it. */
    private static final Set<String> SKIPPED = Set.of("script", "style", "textarea", "title", "template", "noscript",
            "iframe", "xmp", "noembed", "noframes", "svg", "math", "object", "select");
    private static final Pattern EXTERNAL = Pattern.compile("(?i)^(?:https?|mailto):.+");

    private final MarkdownPlaces places;
    private final Map<String, String> slugs = new HashMap<>();
    /** The anchors of the titles, {@code h-7}: a link may name one directly. */
    private final java.util.Set<String> anchors = new java.util.HashSet<>();
    private final HtmlRenderer renderer;
    /** The titles met inside a block being rendered: a title in a quote or a list. */
    private final List<Rendering.Place> nested = new ArrayList<>();
    /** Where the renderer writes each block, emptied before the next one. */
    private final StringBuilder sink = new StringBuilder();
    private final RenderingBuilder builder = new RenderingBuilder();

    private MarkdownRendering(MarkdownPlaces places) {
        this.places = places;
        this.renderer = HtmlRenderer.builder()
                .extensions(EXTENSIONS)
                .nodeRendererFactory(LoreNodes::new)
                .build();
        // In the order of the document, so that the first of two equal titles keeps the plain slug.
        places.numbers.values().forEach(number -> anchors.add("h-" + number));
        places.numbers.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .forEach(entry -> addSlug(places.titles.get(entry.getKey()), "h-" + entry.getValue()));
    }

    /** The rendering of a parsed document whose places were visited. */
    static Rendering render(Node document, MarkdownPlaces places, boolean textTruncated) {
        return new MarkdownRendering(places).write(document, textTruncated);
    }

    private Rendering write(Node document, boolean textTruncated) {
        // One pass of the renderer over the whole document: LoreNodes hands each block to the builder.
        renderer.render(document, sink);
        return builder.build(textTruncated);
    }

    /** The top of the document, block by block: a title opens a section, any other block goes in it. */
    private void blocks(Node document, HtmlNodeRendererContext context) {
        for (Node node = document.getFirstChild(); node != null && !builder.truncated(); node = node.getNext()) {
            sink.setLength(0);
            if (node instanceof Heading heading) {
                Integer number = places.numbers.get(heading);
                if (number != null) {
                    for (Node child = heading.getFirstChild(); child != null; child = child.getNext()) {
                        context.render(child);
                    }
                    builder.section("h-" + number, heading.getLevel(), places.titles.get(heading), sink.toString());
                }
                continue;
            }
            nested.clear();
            context.render(node);
            builder.block(sink.toString(), List.copyOf(nested));
        }
    }

    /** GitHub's anchor of a title, and the same without accents: {@code #erreurs-fréquentes}. */
    private void addSlug(String title, String anchor) {
        String slug = slug(title);
        String unique = slug;
        for (int n = 1; slugs.containsKey(unique); n++) {
            unique = slug + "-" + n;
        }
        slugs.put(unique, anchor);
        slugs.putIfAbsent(plain(unique), anchor);
    }

    static String slug(String title) {
        StringBuilder slug = new StringBuilder();
        title.strip().toLowerCase(Locale.ROOT).codePoints().forEach(point -> {
            if (Character.isLetterOrDigit(point) || point == '-' || point == '_') {
                slug.appendCodePoint(point);
            } else if (Character.isWhitespace(point)) {
                slug.append('-');
            }
        });
        return slug.toString();
    }

    private static String plain(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /** The section a link inside the document leads to, if the document has it. */
    private String target(String fragment) {
        String decoded;
        try {
            decoded = URLDecoder.decode(fragment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            decoded = fragment;
        }
        String key = decoded.toLowerCase(Locale.ROOT);
        if (anchors.contains(key)) {
            return key;
        }
        String found = slugs.get(key);
        return found != null ? found : slugs.get(plain(key));
    }

    /**
     * The HTML written in the file, reduced to the short list: kept tags without their attributes,
     * other tags left out with their text kept, and the content of a script or a style left out
     * too. Tags are not closed here: a {@code <details>} may open in one block and close after the
     * Markdown it holds; Lore balances the whole part when it cleans it.
     */
    static String keptHtml(String literal) {
        StringBuilder out = new StringBuilder();
        Matcher tag = TAG.matcher(literal);
        int last = 0;
        String skipping = null;
        while (tag.find()) {
            if (skipping == null) {
                out.append(htmlText(literal.substring(last, tag.start())));
            }
            last = tag.end();
            if (tag.group(2) == null) {
                continue;
            }
            String name = tag.group(2).toLowerCase(Locale.ROOT);
            boolean closing = !tag.group(1).isEmpty();
            if (skipping != null) {
                if (closing && name.equals(skipping)) {
                    skipping = null;
                }
                continue;
            }
            if (!closing && SKIPPED.contains(name)) {
                if (!tag.group().endsWith("/>")) {
                    skipping = name;
                }
                continue;
            }
            if (KEPT_HTML.contains(name)) {
                out.append(name.equals("br") ? "<br>" : "<" + (closing ? "/" : "") + name + ">");
            }
        }
        if (skipping == null) {
            out.append(htmlText(literal.substring(last)));
        }
        return out.toString();
    }

    /** Text between tags: its entities kept, a stray angle bracket made harmless. */
    private static String htmlText(String text) {
        return text.replace("<", "&lt;").replace(">", "&gt;");
    }

    static boolean external(String destination) {
        return EXTERNAL.matcher(destination.strip()).matches();
    }

    static String externalLink(String destination) {
        return "<a href=\"" + RenderingBuilder.escape(destination.strip())
                + "\" target=\"_blank\" rel=\"noopener noreferrer nofollow\">";
    }

    /** How Lore writes links, images, HTML, code and titles. */
    private final class LoreNodes implements NodeRenderer {

        private final HtmlNodeRendererContext context;
        private final HtmlWriter html;

        LoreNodes(HtmlNodeRendererContext context) {
            this.context = context;
            this.html = context.getWriter();
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Document.class, Link.class, Image.class, HtmlInline.class, HtmlBlock.class,
                    FencedCodeBlock.class, IndentedCodeBlock.class, Heading.class);
        }

        @Override
        public void render(Node node) {
            switch (node) {
                case Document document -> blocks(document, context);
                case Link link -> link(link);
                case Image image -> image(image);
                case HtmlInline inline -> html.raw(keptHtml(inline.getLiteral()));
                case HtmlBlock block -> html.raw(keptHtml(block.getLiteral()));
                case FencedCodeBlock code -> code(code.getLiteral());
                case IndentedCodeBlock code -> code(code.getLiteral());
                case Heading heading -> heading(heading);
                default -> children(node);
            }
        }

        private void link(Link link) {
            String destination = link.getDestination() == null ? "" : link.getDestination();
            if (external(destination)) {
                html.raw(externalLink(destination));
                children(link);
                html.raw("</a>");
            } else if (destination.startsWith("#") && target(destination.substring(1)) != null) {
                html.raw("<a href=\"#" + target(destination.substring(1)) + "\">");
                children(link);
                html.raw("</a>");
            } else {
                // Another file of the repository, or a title the document does not have: its text only.
                children(link);
            }
        }

        private void image(Image image) {
            String alt = MarkdownPlaces.text(image).strip();
            String destination = image.getDestination() == null ? "" : image.getDestination();
            if (external(destination) && !destination.regionMatches(true, 0, "mailto:", 0, 7)) {
                html.raw("<a class=\"lore-image\" href=\"" + RenderingBuilder.escape(destination.strip())
                        + "\" target=\"_blank\" rel=\"noopener noreferrer nofollow\">" + RenderingBuilder.escape(alt) + "</a>");
            } else {
                html.raw("<span class=\"lore-image\">" + RenderingBuilder.escape(alt) + "</span>");
            }
        }

        private void code(String literal) {
            String code = literal.endsWith("\n") ? literal.substring(0, literal.length() - 1) : literal;
            html.raw("<pre><code>" + RenderingBuilder.escape(code) + "</code></pre>");
        }

        /** A title inside a quote or a list: it keeps its number, on itself. */
        private void heading(Heading heading) {
            Integer number = places.numbers.get(heading);
            if (number == null) {
                return;
            }
            String tag = "h" + RenderingBuilder.heading(heading.getLevel());
            html.raw("<" + tag + " id=\"h-" + number + "\">");
            children(heading);
            html.raw("</" + tag + ">");
            nested.add(new Rendering.Place("h-" + number, 0, heading.getLevel(), places.titles.get(heading)));
        }

        private void children(Node node) {
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                context.render(child);
            }
        }
    }
}
