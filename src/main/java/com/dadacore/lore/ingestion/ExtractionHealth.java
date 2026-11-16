package com.dadacore.lore.ingestion;

import org.jspecify.annotations.Nullable;

/** Whether the extraction works, for Administration › Storage and import. */
public interface ExtractionHealth {

    /**
     * @param workers the extractions that can run at the same time
     * @param problem why the last extraction process could not start, or null when it works
     */
    record State(int workers, @Nullable String problem) {
    }

    State state();
}
