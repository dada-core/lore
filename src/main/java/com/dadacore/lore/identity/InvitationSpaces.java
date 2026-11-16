package com.dadacore.lore.identity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The spaces an invitation adds its recipient to ("Spaces to join"). Identity stores the
 * invitation; the module that holds the spaces implements this interface.
 */
public interface InvitationSpaces {

    /**
     * Checks the spaces chosen by the person creating the invitation, then attaches them to it:
     * {@code 400} on the field {@code spaces} when a space is unknown or archived, or a role invalid.
     */
    List<InvitedSpace> attach(Viewer viewer, UUID invitationId, List<SpaceInvitation> spaces);

    Map<UUID, List<InvitedSpace>> spaces(Collection<UUID> invitationIds);

    /** Gives the new member their roles in the spaces of the invitation, and returns these spaces. */
    List<InvitedSpace> join(UUID invitationId, UUID organizationId, UUID userId);
}
