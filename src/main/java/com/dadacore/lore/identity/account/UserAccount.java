package com.dadacore.lore.identity.account;

import java.time.Instant;
import java.util.UUID;

/**
 * @param locale interface language chosen by the person, or null to follow the organization
 */
public record UserAccount(
        UUID id,
        String email,
        String displayName,
        String passwordHash,
        AccountStatus status,
        String locale,
        Instant lastLoginAt,
        Instant createdAt) {

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }
}
