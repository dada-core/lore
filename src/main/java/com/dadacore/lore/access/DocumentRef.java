package com.dadacore.lore.access;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A document as the access rules see it, loaded by the module of the documents within the
 * organization of the request.
 *
 * @param ownerId      the person in charge of the document, who keeps seeing it once restricted
 * @param createdBy    the person who imported it
 * @param versionCount its number of versions: the person who imported it may delete it while
 *                     it has only one
 * @param archived     its status is Archived: only the maintainers of its space see it
 */
public record DocumentRef(UUID id, SpaceRef space, @Nullable UUID ownerId, @Nullable UUID createdBy,
        boolean restricted, int versionCount, boolean archived) {
}
