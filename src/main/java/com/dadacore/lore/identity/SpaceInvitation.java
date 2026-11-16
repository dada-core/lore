package com.dadacore.lore.identity;

import java.util.UUID;

/**
 * A space to join with an invitation, and the role given in it.
 *
 * @param role {@code READER}, {@code CONTRIBUTOR} or {@code MAINTAINER}
 */
public record SpaceInvitation(UUID spaceId, String role) {
}
