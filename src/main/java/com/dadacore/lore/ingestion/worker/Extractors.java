package com.dadacore.lore.ingestion.worker;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.dadacore.lore.ingestion.Extraction;
import com.dadacore.lore.ingestion.Rendering;
import com.dadacore.lore.shared.Texts;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.ZeroByteFileException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.PagedText;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.TeeContentHandler;
import org.commonmark.node.Node;
import org.xml.sax.ContentHandler;

/**
 * The text of a file, by format: PDF and Word with Apache Tika, Markdown with commonmark, text as
 * it is; and, but for a PDF, its rendering for the reader, from the same reading. Runs in the
 * extraction process only.
 */
final class Extractors {

    static final String PDF = "application/pdf";
    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final String MARKDOWN = "text/markdown";
    static final String TEXT = "text/plain";

    /** A page with fewer visible characters is a page without text, an image. */
    static final int EMPTY_PAGE = 20;

    /** The type Tika gives to an Office document protected by a password. */
    private static final MediaType PROTECTED_OFFICE = MediaType.application("x-tika-ooxml-protected");

    private final int maxCharacters;

    Extractors(int maxCharacters) {
        this.maxCharacters = maxCharacters;
    }

    Extraction extract(Path file, String mediaType) throws IOException, ExtractionFailure {
        return switch (mediaType) {
            case PDF -> pdf(file);
            case DOCX -> word(file);
            case MARKDOWN -> markdown(file);
            case TEXT -> text(file);
            default -> throw new ExtractionFailure(ExtractionFailure.UNSUPPORTED_CONTENT, "Unknown type " + mediaType);
        };
    }

    private Extraction pdf(Path file) throws IOException, ExtractionFailure {
        PDFParserConfig config = new PDFParserConfig();
        config.setExtractInlineImages(false);
        config.setExtractActions(false);
        Metadata metadata = new Metadata();
        XhtmlText text = XhtmlText.byPage(maxCharacters);
        parse(new PDFParser(), file, text, text, metadata, config);

        Chunker chunker = new Chunker(maxCharacters);
        int emptyPages = 0;
        List<XhtmlText.Place> pages = text.places();
        for (XhtmlText.Place page : pages) {
            int visible = 0;
            for (String paragraph : page.paragraphs()) {
                visible += (int) paragraph.chars().filter(c -> !Character.isWhitespace(c)).count();
            }
            if (visible < EMPTY_PAGE) {
                emptyPages++;
            }
            chunker.section("page-" + page.number(), page.number(), List.of(), page.paragraphs());
        }
        if (text.stopped()) {
            chunker.markTruncated();
        }
        Integer declared = metadata.getInt(PagedText.N_PAGES);
        int pageCount = declared != null && declared > 0 ? declared : pages.size();
        boolean scanned = pageCount > 0 && !text.stopped() && emptyPages * 2 >= pageCount;
        return result(chunker, scanned, pageCount, null, null);
    }

    private Extraction word(Path file) throws IOException, ExtractionFailure {
        MediaType type;
        try (TikaInputStream stream = TikaInputStream.get(file)) {
            Detector detector = new DefaultDetector();
            type = detector.detect(stream, new Metadata(), new ParseContext());
        }
        if (PROTECTED_OFFICE.equals(type)) {
            throw new ExtractionFailure(ExtractionFailure.ENCRYPTED, "Protected Office document");
        }
        if (!DOCX.equals(type.getBaseType().toString())) {
            throw new ExtractionFailure(ExtractionFailure.UNSUPPORTED_CONTENT, "Not a Word document: " + type);
        }
        XhtmlText text = XhtmlText.byHeading(maxCharacters);
        WordRendering rendering = new WordRendering();
        // The rendering first: it sees the last characters before the text stops the reading.
        parse(new OOXMLParser(), file, new TeeContentHandler(rendering, text), text, new Metadata(), null);
        Chunker chunker = new Chunker(maxCharacters);
        for (XhtmlText.Place place : text.places()) {
            chunker.section("h-" + place.number(), null, place.headings(), place.paragraphs());
        }
        if (text.stopped()) {
            chunker.markTruncated();
        }
        Rendering laidOut;
        try {
            laidOut = rendering.build(chunker.truncated());
        } catch (RuntimeException e) {
            laidOut = null;
        }
        return result(chunker, false, null, null, laidOut);
    }

    private Extraction markdown(Path file) throws IOException {
        TextFiles.Text read = TextFiles.read(file, maxCharacters);
        Node document = MarkdownRendering.PARSER.parse(read.content());
        MarkdownPlaces places = new MarkdownPlaces();
        document.accept(places);
        Chunker chunker = new Chunker(maxCharacters);
        for (XhtmlText.Place place : places.places) {
            chunker.section("h-" + place.number(), null, place.headings(), place.paragraphs());
        }
        if (read.truncated()) {
            chunker.markTruncated();
        }
        Rendering rendering;
        try {
            rendering = MarkdownRendering.render(document, places, chunker.truncated());
        } catch (RuntimeException e) {
            // The text is kept, searchable; the document shows its file only.
            rendering = null;
        }
        return result(chunker, false, null, places.firstTitle, rendering);
    }

    private Extraction text(Path file) throws IOException {
        TextFiles.Text read = TextFiles.read(file, maxCharacters);
        List<String> parts = parts(read.content().replace("\r\n", "\n").replace('\r', '\n'), Chunker.MAX_CHUNK);
        Chunker chunker = new Chunker(maxCharacters);
        RenderingBuilder rendering = new RenderingBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String anchor = "part-" + (i + 1);
            chunker.section(anchor, null, List.of(), List.of(parts.get(i)));
            rendering.section(anchor, 0, null, null);
            // Spaces, tabs and lines as they are written; the reader folds the long lines.
            rendering.block("<pre>" + RenderingBuilder.escape(parts.get(i)) + "</pre>");
        }
        if (read.truncated()) {
            chunker.markTruncated();
        }
        return result(chunker, false, null, null, rendering.build(chunker.truncated()));
    }

    /**
     * A text cut into parts of {@code max} characters at most, between two lines: the same part is
     * a chunk ({@code part-3}) and a section of the rendering. A line longer than a part is cut at a
     * space when it has one.
     */
    static List<String> parts(String text, int max) {
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        for (String line : text.split("(?<=\n)")) {
            String rest = line;
            while (rest.length() > max) {
                if (!part.isEmpty()) {
                    parts.add(part.toString());
                    part.setLength(0);
                }
                int cut = rest.lastIndexOf(' ', max);
                if (cut < max / 2) {
                    cut = Texts.end(rest, max);
                }
                parts.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            if (part.length() + rest.length() > max && !part.isEmpty()) {
                parts.add(part.toString());
                part.setLength(0);
            }
            part.append(rest);
        }
        if (!part.isEmpty()) {
            parts.add(part.toString());
        }
        // A part made of blank lines only has nothing to show or to find.
        parts.removeIf(String::isBlank);
        return parts;
    }

    private static Extraction result(Chunker chunker, boolean scanned, Integer pageCount, String title,
            Rendering rendering) {
        return new Extraction(scanned, Languages.detect(chunker.chunks()), pageCount, chunker.truncated(), title,
                chunker.chunks(), rendering);
    }

    /** Runs a Tika parser, embedded documents left out, and translates its failures. */
    private static void parse(Parser parser, Path file, ContentHandler handler, XhtmlText text, Metadata metadata,
            PDFParserConfig pdf) throws IOException, ExtractionFailure {
        ParseContext context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, new SkipEmbedded());
        if (pdf != null) {
            context.set(PDFParserConfig.class, pdf);
        }
        // A missing file fails here, before the parsing: it may come back, unlike a damaged one.
        TikaInputStream stream = TikaInputStream.get(file);
        try (stream) {
            parser.parse(stream, handler, metadata, context);
        } catch (XhtmlText.Enough enough) {
            // Enough text: the rest is cut.
        } catch (TikaException | org.xml.sax.SAXException | IOException | RuntimeException e) {
            if (text.stopped()) {
                // The same maximum, wrapped by the parser on its way out: the rest is cut.
                return;
            }
            throw failure(e);
        }
    }

    /** The file is there: what cannot be read in it is protected or damaged. */
    private static ExtractionFailure failure(Exception error) {
        if (error instanceof EncryptedDocumentException) {
            return new ExtractionFailure(ExtractionFailure.ENCRYPTED, "Protected by a password", error);
        }
        if (error instanceof ZeroByteFileException) {
            return new ExtractionFailure(ExtractionFailure.CORRUPT, "Empty file", error);
        }
        if (error instanceof TikaException) {
            return new ExtractionFailure(cause(error), error.getMessage(), error);
        }
        // Libraries read damaged files with unchecked exceptions too.
        return new ExtractionFailure(ExtractionFailure.CORRUPT, String.valueOf(error.getMessage()), error);
    }

    /** A cause found deeper in the chain decides: a password, or else a damaged file. */
    private static String cause(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String name = cause.getClass().getName().toLowerCase(Locale.ROOT);
            if (cause instanceof EncryptedDocumentException || name.contains("encrypted") || name.contains("password")) {
                return ExtractionFailure.ENCRYPTED;
            }
        }
        return ExtractionFailure.CORRUPT;
    }

    /** Attachments and embedded objects are not read in this version. */
    private static final class SkipEmbedded implements EmbeddedDocumentExtractor {

        @Override
        public boolean shouldParseEmbedded(Metadata metadata, ParseContext context) {
            return false;
        }

        @Override
        public void parseEmbedded(TikaInputStream stream, ContentHandler handler, Metadata metadata,
                ParseContext context, boolean outputHtml) {
            // Never called: shouldParseEmbedded says no.
        }
    }
}
