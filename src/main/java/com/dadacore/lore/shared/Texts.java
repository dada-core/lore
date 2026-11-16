package com.dadacore.lore.shared;

/** Cuts of texts that never leave half of a character written with two units (an emoji, for instance). */
public final class Texts {

    private Texts() {
    }

    /** The first {@code length} units of the text, one less when the last would be half a character. */
    public static String cut(String text, int length) {
        if (text.length() <= length) {
            return text;
        }
        return text.substring(0, end(text, length));
    }

    /** Where to end a cut at {@code length}: before a character written with two units, not inside it. */
    public static int end(CharSequence text, int length) {
        if (length > 0 && length < text.length() && Character.isHighSurrogate(text.charAt(length - 1))
                && Character.isLowSurrogate(text.charAt(length))) {
            return length - 1;
        }
        return length;
    }
}
