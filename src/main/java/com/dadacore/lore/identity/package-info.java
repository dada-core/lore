/**
 * Accounts, organizations, memberships, sessions and the initial setup.
 *
 * <p>Other modules use {@link com.dadacore.lore.identity.Viewer}, the person making the request, whose
 * rights are recalculated from the database on every request, and
 * {@link com.dadacore.lore.identity.OrganizationMembers} to show or choose people. The module that
 * holds the spaces implements {@link com.dadacore.lore.identity.InvitationSpaces}.
 */
package com.dadacore.lore.identity;
