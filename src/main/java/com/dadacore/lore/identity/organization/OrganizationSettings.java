package com.dadacore.lore.identity.organization;

import java.util.UUID;

/**
 * Settings of an organization, changed in Administration.
 *
 * @param publicUrl address entered at setup; ignored when {@code LORE_PUBLIC_URL} is set
 */
public record OrganizationSettings(
        UUID organizationId,
        String defaultLocale,
        String timeZone,
        String publicUrl,
        int passwordMinLength,
        int loginMaxAttempts,
        int loginLockoutMinutes,
        int sessionTimeoutMinutes,
        int invitationValidityHours) {

    public static final int DEFAULT_PASSWORD_MIN_LENGTH = 12;

    public static OrganizationSettings defaults(UUID organizationId, String locale, String timeZone, String publicUrl) {
        return new OrganizationSettings(organizationId, locale, timeZone, publicUrl,
                DEFAULT_PASSWORD_MIN_LENGTH, 5, 15, 8 * 60, 7 * 24);
    }
}
