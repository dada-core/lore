/**
 * The search in the documents: the routes, the analysis of what is typed, the form of the results
 * and the limits (time, searches at once).
 *
 * <p>{@link com.dadacore.lore.search.SearchIndex} finds the documents; the module of the documents
 * implements it, with the permissions written in its query. This module reads no table and knows
 * no rule of access: the assistant of a later version will search through the same port, with the
 * rights of the person who asks. Nothing that is searched is ever written, nor logged.
 */
@ApplicationModule(allowedDependencies = { "identity", "shared" })
package com.dadacore.lore.search;

import org.springframework.modulith.ApplicationModule;
