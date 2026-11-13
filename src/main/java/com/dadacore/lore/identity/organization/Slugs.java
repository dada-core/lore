package com.dadacore.lore.identity.organization;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Short addresses derived from a name: "DadaGroup Réseaux" gives "dadagroup-reseaux".
 */
public final class Slugs {

    private Slugs() {
    }

    public static String of(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = ascii.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "organisation" : slug;
    }
}
