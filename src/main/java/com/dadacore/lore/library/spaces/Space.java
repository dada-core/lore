package com.dadacore.lore.library.spaces;

import java.time.Instant;
import java.util.UUID;

import com.dadacore.lore.access.SpaceRef;
import org.jspecify.annotations.Nullable;

/**
 * A space, as stored.
 *
 * @param slug       the address of the space in the interface, unique in the organization
 * @param updatedAt  the last change of the space or of its folders
 * @param archivedAt null while the space is active
 */
public record Space(
        UUID id,
        UUID organizationId,
        String name,
        String slug,
        @Nullable String description,
        String color,
        @Nullable UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant archivedAt) {

    public boolean archived() {
        return archivedAt != null;
    }

    public SpaceRef ref() {
        return new SpaceRef(id, organizationId, name, archived());
    }
}
