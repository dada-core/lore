package com.dadacore.lore.ingestion.worker;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.dadacore.lore.ingestion.Extraction;
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
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.xml.sax.ContentHandler;

/**
 * The text of a file, by format: PDF and Word with Apache Tika, Markdown with commonmark, text as
 * it is. Runs in the extraction process only.
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
        parse(new PDFParser(), file, text, metadata, config);

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
        return result(chunker, scanned, pageCount, null);
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
        parse(new OOXMLParser(), file, text, new Metadata(), null);
        Chunker chunker = new Chunker(maxCharacters);
        for (XhtmlText.Place place : text.places()) {
            chunker.section("h-" + place.number(), null, place.headings(), place.paragraphs());
        }
        if (text.stopped()) {
            chunker.markTruncated();
        }
        return result(chunker, false, null, null);
    }

    private Extraction markdown(Path file) throws IOException {
        TextFiles.Text read = TextFiles.read(file, maxCharacters);
        Node document = org.commonmark.parser.Parser.builder().build().parse(read.content());
        MarkdownPlaces places = new MarkdownPlaces();
        document.accept(places);
        Chunker chunker = new Chunker(maxCharacters);
        for (XhtmlText.Place place : places.places) {
            chunker.section("h-" + place.number(), null, place.headings(), place.paragraphs());
        }
        if (read.truncated()) {
            chunker.markTruncated();
        }
        return result(chunker, false, null, places.firstTitle);
    }

    private Extraction text(Path file) throws IOException {
        TextFiles.Text read = TextFiles.read(file, maxCharacters);
        List<String> paragraphs = List.of(read.content().replace("\r\n", "\n").split("\\n\\s*\\n"));
        Chunker chunker = new Chunker(maxCharacters);
        List<List<String>> groups = Chunker.pack(paragraphs);
        for (int i = 0; i < groups.size(); i++) {
            chunker.section("part-" + (i + 1), null, List.of(), groups.get(i));
        }
        if (read.truncated()) {
            chunker.markTruncated();
        }
        return result(chunker, false, null, null);
    }

    private static Extraction result(Chunker chunker, boolean scanned, Integer pageCount, String title) {
        return new Extraction(scanned, Languages.detect(chunker.chunks()), pageCount, chunker.truncated(), title,
                chunker.chunks());
    }

    /** Runs a Tika parser, embedded documents left out, and translates its failures. */
    private static void parse(Parser parser, Path file, ContentHandler handler, Metadata metadata,
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
            if (handler instanceof XhtmlText text && text.stopped()) {
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

    /** The places of a Markdown document: the part before the first title, then one per title. */
    private static final class MarkdownPlaces extends AbstractVisitor {

        private final List<XhtmlText.Place> places = new ArrayList<>();
        private final List<String> path = new ArrayList<>();
        private XhtmlText.Place current = new XhtmlText.Place(0, List.of(), new ArrayList<>());
        private String firstTitle;
        private int titles;

        MarkdownPlaces() {
            places.add(current);
        }

        @Override
        public void visit(Heading heading) {
            String title = XhtmlText.heading(text(heading));
            if (title.isEmpty()) {
                return;
            }
            if (heading.getLevel() == 1 && firstTitle == null) {
                firstTitle = title;
            }
            int level = Math.min(heading.getLevel(), path.size() + 1);
            while (path.size() >= level) {
                path.removeLast();
            }
            path.add(title);
            titles++;
            current = new XhtmlText.Place(titles, List.copyOf(path), new ArrayList<>());
            places.add(current);
        }

        @Override
        public void visit(Paragraph paragraph) {
            current.paragraphs().add(text(paragraph));
        }

        @Override
        public void visit(FencedCodeBlock code) {
            current.paragraphs().add(code.getLiteral());
        }

        @Override
        public void visit(IndentedCodeBlock code) {
            current.paragraphs().add(code.getLiteral());
        }

        @Override
        public void visit(ListItem item) {
            // Each item is a paragraph of its own, read through its children.
            visitChildren(item);
        }

        @Override
        public void visit(BlockQuote quote) {
            visitChildren(quote);
        }

        private static String text(Node node) {
            StringBuilder text = new StringBuilder();
            node.accept(new AbstractVisitor() {
                @Override
                public void visit(Text literal) {
                    text.append(literal.getLiteral());
                }

                @Override
                public void visit(Code code) {
                    text.append(code.getLiteral());
                }

                @Override
                public void visit(SoftLineBreak lineBreak) {
                    text.append(' ');
                }

                @Override
                public void visit(HardLineBreak lineBreak) {
                    text.append('\n');
                }
            });
            return text.toString();
        }
    }
}
