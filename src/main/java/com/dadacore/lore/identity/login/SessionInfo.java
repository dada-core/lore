package com.dadacore.lore.identity.login;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * What the interface needs to start: is the installation configured, which organization, who is
 * signed in.
 *
 * @param organization null until the initial setup is done
 * @param user         null without a valid session
 */
public record SessionInfo(boolean setupRequired, @Nullable OrganizationSummary organization,
        @Nullable CurrentUser user) {

    /**
     * @param timeZone dates are displayed in the organization's time zone
     */
    public record OrganizationSummary(String name, String defaultLocale, String timeZone) {
    }

    /**
     * @param locale interface language: the person's choice, otherwise the organization's
     * @param role   OWNER, ADMIN or MEMBER
     */
    public record CurrentUser(UUID id, String displayName, String email, String locale, String role) {
    }
}
