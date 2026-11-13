package com.dadacore.lore.identity.organization;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

import com.dadacore.lore.LoreProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The address at which the team opens Lore, used in invitation and reset links.
 *
 * <p>{@code LORE_PUBLIC_URL} takes precedence: the administration then shows it read-only.
 * Otherwise the address entered at setup applies, and failing that the address of the request.
 */
@Component
public class PublicUrl {

    private final String fromEnvironment;

    public PublicUrl(LoreProperties properties) {
        URI configured = properties.publicUrl();
        this.fromEnvironment = configured == null ? null : normalize(configured.toString());
        if (configured != null && fromEnvironment == null) {
            throw new IllegalStateException(
                    "LORE_PUBLIC_URL must be a complete http or https address, without query or fragment: "
                            + "the address your team types to open Lore");
        }
    }

    public Optional<String> fromEnvironment() {
        return Optional.ofNullable(fromEnvironment);
    }

    /** True when the configured address uses HTTPS: cookies are then marked Secure. */
    public boolean isSecure() {
        return fromEnvironment != null && fromEnvironment.startsWith("https://");
    }

    public String resolve(OrganizationSettings settings, HttpServletRequest request) {
        if (fromEnvironment != null) {
            return fromEnvironment;
        }
        if (settings != null && settings.publicUrl() != null) {
            return settings.publicUrl();
        }
        if (request != null) {
            return normalize(ServletUriComponentsBuilder.fromContextPath(request).build().toUriString());
        }
        return "http://localhost:8080";
    }

    /**
     * Returns the address without a trailing slash, or null when it is not a valid http or https
     * address without query or fragment.
     */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(value.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            if (uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawUserInfo() != null) {
                return null;
            }
            String text = uri.toString();
            while (text.endsWith("/")) {
                text = text.substring(0, text.length() - 1);
            }
            return text;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
