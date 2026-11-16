package com.dadacore.lore.ingestion;

import java.util.Set;
import java.util.UUID;

/**
 * Receives the outcome of each extraction, inside the transaction that ends its job. Implemented
 * by the module that owns the versions of the documents: this module does not know them.
 */
public interface ExtractionResults {

    /**
     * The failures that may pass: tried again, then offered to "Retry". The interface keeps the
     * same list ({@code web/src/documents/documents.tsx}), checked by a test.
     */
    Set<String> PASSING_FAILURES = Set.of("timeout", "extractor_crashed", "file_missing");

    void extracted(UUID versionId, Extraction extraction);

    /**
     * @param code {@code encrypted}, {@code corrupt} or {@code unsupported_content}, which are
     *             final; or {@code timeout}, {@code extractor_crashed} or {@code file_missing},
     *             once the new attempts are used up
     */
    void failed(UUID versionId, String code);
}
