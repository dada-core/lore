package com.dadacore.lore.library.documents;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.dadacore.lore.shared.ApiException;
import com.dadacore.lore.shared.FieldError;
import org.springframework.http.HttpStatus;

/**
 * The formats accepted at import, recognized by the extension of the file and by its first
 * bytes: the type given by the browser is never used. The finer checks (a real Word document,
 * the encoding of a text) happen in the extraction process, which alone opens archives.
 */
enum Formats {

    PDF("application/pdf", List.of("pdf")),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", List.of("docx")),
    MARKDOWN("text/markdown", List.of("md", "markdown")),
    TEXT("text/plain", List.of("txt"));

    static final List<String> EXTENSIONS = Arrays.stream(values()).flatMap(f -> f.extensions.stream()).toList();

    private static final byte[] PDF_START = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] ZIP = {'P', 'K', 3, 4};
    private static final byte[] UTF16_LE = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] UTF16_BE = {(byte) 0xFE, (byte) 0xFF};
    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private final String mediaType;
    private final List<String> extensions;

    Formats(String mediaType, List<String> extensions) {
        this.mediaType = mediaType;
        this.extensions = extensions;
    }

    String mediaType() {
        return mediaType;
    }

    /**
     * The format of a received file. Field errors on {@code file}: {@code legacy_word} for a
     * {@code .doc}, {@code unsupported_format} with the accepted extensions, {@code content_mismatch}
     * when the content is not of the format its name announces.
     */
    static Formats detect(String filename, byte[] head) {
        String name = filename.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1);
        if (extension.equals("doc")) {
            throw rejected("legacy_word", Map.of());
        }
        Formats format = Arrays.stream(values()).filter(f -> f.extensions.contains(extension)).findFirst()
                .orElseThrow(() -> rejected("unsupported_format", Map.of("formats", String.join(", ", EXTENSIONS))));
        boolean matches = switch (format) {
            case PDF -> contains(head, PDF_START, 1024);
            // A Word document is a ZIP archive; protected by a password, an OLE2 container.
            case DOCX -> startsWith(head, ZIP) || startsWith(head, OLE2);
            // Without a null byte, unless it is UTF-16 with its mark (the "Unicode" of Notepad).
            case MARKDOWN, TEXT -> startsWith(head, UTF16_LE) || startsWith(head, UTF16_BE)
                    || !contains(head, new byte[] {0}, head.length);
        };
        if (!matches) {
            throw rejected("content_mismatch", Map.of());
        }
        return format;
    }

    private static boolean startsWith(byte[] head, byte[] start) {
        return head.length >= start.length && Arrays.equals(head, 0, start.length, start, 0, start.length);
    }

    /** True when {@code part} appears in the first {@code within} bytes. */
    private static boolean contains(byte[] head, byte[] part, int within) {
        int limit = Math.min(head.length, within) - part.length;
        for (int i = 0; i <= limit; i++) {
            if (Arrays.equals(head, i, i + part.length, part, 0, part.length)) {
                return true;
            }
        }
        return false;
    }

    private static ApiException rejected(String code, Map<String, Object> params) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request")
                .with("errors", List.of(new FieldError("file", code, params)));
    }
}
