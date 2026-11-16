package com.dadacore.lore.identity;

import java.util.UUID;

/**
 * A space an invitation adds its recipient to, as shown on the invitation.
 */
public record InvitedSpace(UUID id, String name, String color, String role) {
}
