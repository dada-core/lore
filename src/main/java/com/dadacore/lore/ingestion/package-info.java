/**
 * The extraction of the text of the imported files, away from the application (ADR 0003).
 *
 * <p>{@link com.dadacore.lore.ingestion.ExtractionQueue} receives the jobs, in the transaction of
 * the import. Worker threads take them from the database, have them run by extraction processes
 * started by Lore itself ({@link com.dadacore.lore.ingestion.ExtractionWorker}), and hand the result
 * to {@link com.dadacore.lore.ingestion.ExtractionResults}, which the module of the documents
 * implements: this module knows neither documents nor permissions.
 */
package com.dadacore.lore.ingestion;
