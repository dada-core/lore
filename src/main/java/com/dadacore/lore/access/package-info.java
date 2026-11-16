/**
 * Who can see and do what: the roles in the spaces, and later the restricted documents.
 *
 * <p>The rules are written here only. {@link com.dadacore.lore.access.SpaceAccess} gives the role of
 * a person in a space, and the condition "spaces visible by this person" that every list adds to its
 * own query. A space the person cannot see answers {@code 404}, never {@code 403}, so that its
 * existence is not revealed.
 *
 * <p>The module of the spaces (library) loads a space within the organization of the request and
 * passes it here as a {@link com.dadacore.lore.access.SpaceRef}: this module depends on identity, not on
 * library.
 */
package com.dadacore.lore.access;
