package com.dadacore.lore.ingestion;

import com.dadacore.lore.ingestion.worker.WorkerMain;

/**
 * The extraction process: Lore starts this class in a new Java process, without Spring and
 * without the database, to extract the text of files away from the application (ADR 0003). A
 * file that exhausts memory or never ends only stops that process.
 *
 * <p>From the jar of Lore, it is started through the launcher of Spring Boot that takes the class
 * to run from {@code loader.main}; otherwise (development, tests) directly, on the same class path.
 */
public final class ExtractionWorker {

    private ExtractionWorker() {
    }

    /** Reads the requests on the standard input until it is closed. */
    public static void main(String[] args) {
        WorkerMain.run();
    }
}
