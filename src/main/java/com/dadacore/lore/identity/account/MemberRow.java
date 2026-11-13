package com.dadacore.lore.identity.account;

import java.time.Instant;
import java.util.UUID;

import com.dadacore.lore.identity.OrganizationRole;

/**
 * A member of an organization, as listed in Administration, Members.
 */
public record MemberRow(
        UUID userId,
        String displayName,
        String email,
        AccountStatus status,
        OrganizationRole role,
        Instant lastLoginAt,
        Instant joinedAt) {
}
