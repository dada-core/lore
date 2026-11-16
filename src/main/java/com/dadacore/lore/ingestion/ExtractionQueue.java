package com.dadacore.lore.ingestion;

import java.util.UUID;

import com.dadacore.lore.ingestion.jobs.ExtractionJobs;
import com.dadacore.lore.ingestion.jobs.ExtractionSignal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The extractions to run. A job added in the transaction of an import only exists once that
 * transaction is committed; the workers are woken up then.
 */
@Service
public class ExtractionQueue {

    private final ExtractionJobs jobs;
    private final ExtractionSignal signal;

    public ExtractionQueue(ExtractionJobs jobs, ExtractionSignal signal) {
        this.jobs = jobs;
        this.signal = signal;
    }

    /** The text of a stored file to extract, for one version of a document. */
    public void enqueue(UUID versionId, UUID organizationId, String sha256, String mediaType) {
        jobs.add(versionId, organizationId, sha256, mediaType);
        wakeAfterCommit();
    }

    private void wakeAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    signal.wake();
                }
            });
        } else {
            signal.wake();
        }
    }
}
