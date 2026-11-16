package com.dadacore.lore.shared;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Short addresses derived from a name: "DadaGroup Réseaux" gives "dadagroup-reseaux".
 */
public final class Slugs {

    public static final int MAX_LENGTH = 60;
    private static final Pattern VALID = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private Slugs() {
    }

    /** The address of an organization. */
    public static String of(String name) {
        return of(name, "organisation");
    }

    /** @param fallback the address of a name without any letter or digit */
    public static String of(String name, String fallback) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = ascii.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (slug.length() > MAX_LENGTH) {
            slug = slug.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? fallback : slug;
    }

    /** Lower case letters and digits, separated by single dashes. */
    public static boolean isValid(String slug) {
        return slug.length() <= MAX_LENGTH && VALID.matcher(slug).matches();
    }
}
