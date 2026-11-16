package com.dadacore.lore.ingestion.worker;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.channels.Channels;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a text or Markdown file: UTF-8 (with or without its mark), UTF-16 with its mark, or
 * Windows-1252 when the bytes are not UTF-8, as many older files are.
 */
final class TextFiles {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    /** The bytes looked at to choose between UTF-8 and Windows-1252. */
    static final int SAMPLE = 16 * 1024 * 1024;

    private TextFiles() {
    }

    /** The text read, and whether it was cut at the maximum. */
    record Text(String content, boolean truncated) {
    }

    static Text read(Path file, int maxCharacters) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(3);
        }
        if (head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            return read(file, 3, StandardCharsets.UTF_8, CodingErrorAction.REPLACE, maxCharacters);
        }
        if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
            return read(file, 2, StandardCharsets.UTF_16LE, CodingErrorAction.REPLACE, maxCharacters);
        }
        if (head.length >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
            return read(file, 2, StandardCharsets.UTF_16BE, CodingErrorAction.REPLACE, maxCharacters);
        }
        try {
            return read(file, 0, StandardCharsets.UTF_8, CodingErrorAction.REPORT, maxCharacters);
        } catch (CharacterCodingException notUtf8) {
            // A few invalid bytes in a UTF-8 text (a copy and paste) keep it UTF-8: an old Windows
            // file writes its accents as single bytes, never as valid sequences of UTF-8.
            Charset charset = mostlyUtf8(file) ? StandardCharsets.UTF_8 : WINDOWS_1252;
            return read(file, 0, charset, CodingErrorAction.REPLACE, maxCharacters);
        }
    }

    /** In the first {@value #SAMPLE} bytes, more valid sequences of UTF-8 than invalid bytes. */
    static boolean mostlyUtf8(Path file) throws IOException {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes(SAMPLE);
        }
        int valid = 0;
        int invalid = 0;
        int i = 0;
        while (i < bytes.length) {
            int first = bytes[i] & 0xFF;
            int following = first < 0x80 ? 0
                    : first >= 0xC2 && first <= 0xDF ? 1
                    : first >= 0xE0 && first <= 0xEF ? 2
                    : first >= 0xF0 && first <= 0xF4 ? 3
                    : -1;
            if (following == 0) {
                i++;
                continue;
            }
            if (following < 0) {
                invalid++;
                i++;
                continue;
            }
            if (i + following >= bytes.length) {
                // Cut by the end of the sample.
                break;
            }
            boolean sequence = true;
            for (int k = 1; k <= following; k++) {
                if ((bytes[i + k] & 0xC0) != 0x80) {
                    sequence = false;
                    break;
                }
            }
            if (sequence) {
                valid++;
                i += following + 1;
            } else {
                invalid++;
                i++;
            }
        }
        return valid > invalid;
    }

    private static Text read(Path file, int skip, Charset charset, CodingErrorAction errors, int maxCharacters)
            throws IOException {
        CharsetDecoder decoder = charset.newDecoder().onMalformedInput(errors).onUnmappableCharacter(errors);
        try (InputStream in = Files.newInputStream(file);
                Reader reader = Channels.newReader(Channels.newChannel(in), decoder, -1)) {
            in.skipNBytes(skip);
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (text.length() + read > maxCharacters) {
                    int kept = maxCharacters - text.length();
                    if (kept > 0 && Character.isHighSurrogate(buffer[kept - 1])) {
                        kept--;
                    }
                    text.append(buffer, 0, kept);
                    return new Text(text.toString(), true);
                }
                text.append(buffer, 0, read);
            }
            return new Text(text.toString(), false);
        }
    }
}
