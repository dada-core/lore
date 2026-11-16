package com.dadacore.lore.access;

import java.util.UUID;

import com.dadacore.lore.shared.ApiException;

/**
 * The space a rule applies to, as loaded by the module that holds the spaces.
 *
 * @param name     the name of the space, for the audit log
 * @param archived an archived space is only seen by its maintainers, and can no longer be changed
 */
public record SpaceRef(UUID id, UUID organizationId, String name, boolean archived) {

    /** An archived space can no longer be changed: {@code 409 space_archived}. */
    public void requireActive() {
        if (archived) {
            throw ApiException.conflict("space_archived");
        }
    }
}
