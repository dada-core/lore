/**
 * Spaces and their folders; documents and their versions will join them with the import.
 *
 * <p>Every route loads its space within the organization of the request, then asks the access
 * module for the role of the person: a space they cannot see answers {@code 404}.
 */
package com.dadacore.lore.library;
