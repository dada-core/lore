package com.dadacore.lore.library.documents;

import java.util.regex.Pattern;

import com.dadacore.lore.shared.Texts;

/** The name of a received file, cleaned for display, and the title a document gets from it. */
final class Titles {

    static final int MAX_TITLE = 200;
    static final int MAX_FILENAME = 255;

    private static final Pattern CONTROL = Pattern.compile("\\p{Cc}");
    private static final Pattern SEPARATORS = Pattern.compile("[-_\\s]+");

    private Titles() {
    }

    /** The last part of the name only, without control characters, 255 characters at most. */
    static String filename(String original) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = CONTROL.matcher(name).replaceAll("").strip();
        if (name.isEmpty()) {
            return "document";
        }
        if (name.length() > MAX_FILENAME) {
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";
            name = Texts.cut(name, MAX_FILENAME - extension.length()) + extension;
        }
        return name;
    }

    /** {@code plan-reprise_2026.docx} gives "Plan reprise 2026". */
    static String fromFilename(String filename) {
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        String title = SEPARATORS.matcher(base).replaceAll(" ").strip();
        if (title.isEmpty()) {
            title = filename;
        }
        title = Character.toUpperCase(title.charAt(0)) + title.substring(1);
        return Texts.cut(title, MAX_TITLE).strip();
    }
}
