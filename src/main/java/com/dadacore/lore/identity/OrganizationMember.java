package com.dadacore.lore.identity;

import java.util.UUID;

/**
 * A member of an organization, as other modules show it.
 *
 * @param active false when the account is disabled
 */
public record OrganizationMember(UUID id, String displayName, String email, OrganizationRole role, boolean active) {
}
