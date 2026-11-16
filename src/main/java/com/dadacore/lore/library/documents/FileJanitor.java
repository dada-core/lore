package com.dadacore.lore.library.documents;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import com.dadacore.lore.storage.FileStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes the files that no version uses any more, once the transaction that removed their last
 * version is committed. The same lock as the import of a content protects each deletion, so that a
 * file is never deleted under an import of the same content.
 */
@Service
public class FileJanitor {

    private static final Logger log = LoggerFactory.getLogger(FileJanitor.class);

    /** Contents locked and checked in one transaction: well within the lock table of PostgreSQL. */
    static final int BATCH = 500;

    private final DocumentRepository documents;
    private final FileStore store;
    private final TransactionTemplate transaction;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("lore-file-janitor").factory());

    FileJanitor(DocumentRepository documents, FileStore store, PlatformTransactionManager transactions) {
        this.documents = documents;
        this.store = store;
        // Run after the commit of another transaction: a new one of its own each time.
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @PreDestroy
    void stop() throws InterruptedException {
        worker.shutdown();
        worker.awaitTermination(30, TimeUnit.SECONDS);
    }

    /** The contents of all the versions of a space, read before it is deleted. */
    public List<String> contentsOfSpace(UUID spaceId) {
        return documents.contents(null, spaceId);
    }

    /** After the commit of the current transaction: deletes these contents if nothing uses them. */
    public void afterCommit(UUID organizationId, Collection<String> contents) {
        if (contents.isEmpty()) {
            return;
        }
        List<String> copy = List.copyOf(contents);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // Apart from the request, which still holds its connection here: one batch at a time,
                // never a second connection per request.
                try {
                    worker.execute(() -> deleteUnused(organizationId, copy));
                } catch (RejectedExecutionException stopping) {
                    log.info("Lore stops: {} unused files left for later", copy.size());
                }
            }
        });
    }

    /** Deletes one content if no version of the organization uses it. */
    void deleteIfUnused(UUID organizationId, String sha256) {
        deleteUnused(organizationId, List.of(sha256));
    }

    /** Deletes the contents no version of the organization uses, {@value #BATCH} per transaction. */
    void deleteUnused(UUID organizationId, Collection<String> contents) {
        // Sorted: two deletions never wait for each other's locks in opposite orders.
        List<String> sorted = contents.stream().distinct().sorted().toList();
        for (int start = 0; start < sorted.size(); start += BATCH) {
            List<String> batch = sorted.subList(start, Math.min(sorted.size(), start + BATCH));
            try {
                transaction.executeWithoutResult(status -> {
                    documents.lockContents(organizationId, batch);
                    documents.unused(organizationId, batch).forEach(sha256 -> store.delete(organizationId, sha256));
                });
            } catch (RuntimeException e) {
                // A file left behind is harmless: it is never shown, and a later import reuses it.
                log.warn("Could not delete {} unused files: {}", batch.size(), e.getMessage());
            }
        }
    }
}
