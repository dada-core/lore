package com.dadacore.lore.identity;

import java.util.UUID;

import com.dadacore.lore.shared.ApiException;

/**
 * The signed-in person making the request, loaded from the database on every request: a role
 * change or a deactivation applies immediately.
 *
 * <p>Controllers receive it as a method parameter. A request without a valid session is answered
 * with {@code 401} before reaching them.
 *
 * @param locale the interface language: the person's choice, otherwise the organization's
 */
public record Viewer(
        UUID userId,
        UUID organizationId,
        OrganizationRole role,
        String displayName,
        String email,
        String locale,
        String sessionId,
        String ip) {

    public boolean isAdmin() {
        return role.isAdmin();
    }

    public boolean isOwner() {
        return role == OrganizationRole.OWNER;
    }

    /** Organization administration is reserved to owners and administrators. */
    public void requireAdmin() {
        if (!isAdmin()) {
            throw ApiException.forbidden("admin_only");
        }
    }
}
