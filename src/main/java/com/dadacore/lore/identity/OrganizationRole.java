package com.dadacore.lore.identity;

/**
 * Role in the organization. Owners and administrators can access everything the organization
 * holds; only owners grant or withdraw the owner role.
 */
public enum OrganizationRole {
    OWNER,
    ADMIN,
    MEMBER;

    public boolean isAdmin() {
        return this == OWNER || this == ADMIN;
    }
}
