package com.dadacore.lore.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/** Small documents made by the tests, so that no sample file has to be kept in the repository. */
public final class SampleFiles {

    private SampleFiles() {
    }

    /** A PDF with one page per text; a null text leaves the page without text, like a scan. */
    public static byte[] pdf(String... pages) {
        return protectedPdf(null, pages);
    }

    /** A PDF that opens only with a password. */
    public static byte[] protectedPdf(String text) {
        return protectedPdf("secret", text);
    }

    private static byte[] protectedPdf(String password, String... pages) {
        try (PDDocument document = new PDDocument()) {
            for (String text : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (text == null) {
                    continue;
                }
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                    content.setLeading(14);
                    content.newLineAtOffset(50, 720);
                    for (String line : text.split("\n")) {
                        content.showText(line);
                        content.newLine();
                    }
                    content.endText();
                }
            }
            if (password != null) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy(password + "-owner", password,
                        new AccessPermission());
                policy.setEncryptionKeyLength(256);
                document.protect(policy);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A Word document, written by hand as Word stores it, so that the tests need no more of the
     * Office libraries than the application. A line starting with {@code #}, {@code ##}... is a
     * title of that level; the others are paragraphs.
     */
    public static byte[] docx(String... lines) {
        return docxWithDoctype("", lines);
    }

    /** A Word document whose main part starts with this declaration; its lines are then raw XML text, for the tests of entities. */
    public static byte[] docxWithDoctype(String doctype, String... lines) {
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            int level = 0;
            while (level < line.length() && line.charAt(level) == '#') {
                level++;
            }
            body.append("<w:p>");
            if (level > 0) {
                body.append("<w:pPr><w:pStyle w:val=\"Heading").append(level).append("\"/></w:pPr>");
            }
            body.append("<w:r><w:t xml:space=\"preserve\">").append(doctype.isEmpty() ? xml(line.substring(level).strip()) : line.substring(level).strip())
                    .append("</w:t></w:r></w:p>");
        }
        StringBuilder styles = new StringBuilder();
        for (int level = 1; level <= 3; level++) {
            styles.append("<w:style w:type=\"paragraph\" w:styleId=\"Heading").append(level)
                    .append("\"><w:name w:val=\"heading ").append(level).append("\"/></w:style>");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                entry(zip, "[Content_Types].xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                        <Default Extension="xml" ContentType="application/xml"/>
                        <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                        <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
                        </Types>""");
                entry(zip, "_rels/.rels", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                        </Relationships>""");
                entry(zip, "word/_rels/document.xml.rels", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                        </Relationships>""");
                entry(zip, "word/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<w:styles xmlns:w=\"" + WORD + "\">" + styles + "</w:styles>");
                entry(zip, "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + doctype + "<w:document xmlns:w=\"" + WORD + "\"><w:body>" + body + "</w:body></w:document>");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String RELATIONSHIPS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    /**
     * A Word document written from the XML of its body, as Word writes it: titles {@code Heading1}
     * to {@code Heading3}, a preformatted style {@code Code}, a numbered list (numId 1, two levels)
     * and a bulleted one (numId 2). {@code links} are the targets of the external links, named
     * {@code rId10}, {@code rId11}...
     */
    public static byte[] docxBody(String body, String... links) {
        return docxPackage(null, body, links);
    }

    /** As {@link #docxBody}, with a title in the properties of the file, as Word keeps it in {@code docProps/core.xml}. */
    public static byte[] docxWithTitle(String title, String body) {
        return docxPackage(title, body);
    }

    private static byte[] docxPackage(String title, String body, String... links) {
        StringBuilder styles = new StringBuilder();
        for (int level = 1; level <= 3; level++) {
            styles.append("<w:style w:type=\"paragraph\" w:styleId=\"Heading").append(level)
                    .append("\"><w:name w:val=\"heading ").append(level).append("\"/></w:style>");
        }
        styles.append("<w:style w:type=\"paragraph\" w:styleId=\"Code\"><w:name w:val=\"HTML Preformatted\"/></w:style>");
        String numbering = "<w:numbering xmlns:w=\"" + WORD + "\">"
                + "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/>"
                + "<w:lvlText w:val=\"%1.\"/></w:lvl><w:lvl w:ilvl=\"1\"><w:start w:val=\"1\"/><w:numFmt w:val=\"lowerLetter\"/>"
                + "<w:lvlText w:val=\"%2)\"/></w:lvl></w:abstractNum>"
                + "<w:abstractNum w:abstractNumId=\"1\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/>"
                + "<w:lvlText w:val=\"•\"/></w:lvl></w:abstractNum>"
                + "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num><w:num w:numId=\"2\"><w:abstractNumId w:val=\"1\"/></w:num>"
                + "</w:numbering>";
        StringBuilder relationships = new StringBuilder();
        for (int i = 0; i < links.length; i++) {
            relationships.append("<Relationship Id=\"rId").append(10 + i)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"")
                    .append(xml(links[i]).replace("\"", "&quot;")).append("\" TargetMode=\"External\"/>");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                entry(zip, "[Content_Types].xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                        <Default Extension="xml" ContentType="application/xml"/>
                        <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                        <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
                        <Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>
                        <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
                        </Types>""");
                entry(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                        + (title == null ? "" : "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>")
                        + "</Relationships>");
                if (title != null) {
                    entry(zip, "docProps/core.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                            + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
                            + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>" + xml(title) + "</dc:title></cp:coreProperties>");
                }
                entry(zip, "word/_rels/document.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                        + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>"
                        + relationships + "</Relationships>");
                entry(zip, "word/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<w:styles xmlns:w=\"" + WORD + "\">" + styles + "</w:styles>");
                entry(zip, "word/numbering.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" + numbering);
                entry(zip, "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<w:document xmlns:w=\"" + WORD + "\" xmlns:r=\"" + RELATIONSHIPS + "\"><w:body>" + body
                        + "</w:body></w:document>");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A Word document protected by a password: an OLE2 container, as Word writes it. */
    public static byte[] protectedDocx(String text) {
        try (POIFSFileSystem container = new POIFSFileSystem()) {
            EncryptionInfo info = new EncryptionInfo(EncryptionMode.agile);
            Encryptor encryptor = info.getEncryptor();
            encryptor.confirmPassword("secret");
            try (OutputStream data = encryptor.getDataStream(container)) {
                data.write(docx(text));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            container.writeFilesystem(out);
            return out.toByteArray();
        } catch (IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An OLE2 container that holds no document: not the Word document its name announces. */
    public static byte[] emptyOle2() {
        try (POIFSFileSystem container = new POIFSFileSystem()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            container.writeFilesystem(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A Word document whose text is compressed a thousand times: a few kilobytes on disk that
     * would fill the memory once opened.
     */
    public static byte[] compressionBomb() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                entry(zip, "[Content_Types].xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                        <Default Extension="xml" ContentType="application/xml"/>
                        <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                        </Types>""");
                entry(zip, "_rels/.rels", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                        </Relationships>""");
                zip.putNextEntry(new ZipEntry("word/document.xml"));
                zip.write("<?xml version=\"1.0\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>"
                        .getBytes(StandardCharsets.UTF_8));
                byte[] spaces = " ".repeat(1 << 20).getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < 20; i++) {
                    zip.write(spaces);
                }
                zip.write("</w:t></w:r></w:p></w:body></w:document>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] text(String text, Charset charset) {
        return text.getBytes(charset);
    }

    public static Path write(Path folder, String name, byte[] content) {
        try {
            return Files.write(folder.resolve(name), content);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
