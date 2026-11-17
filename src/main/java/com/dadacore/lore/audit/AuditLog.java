package com.dadacore.lore.audit;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The audit log of sensitive operations: sign-in failures, account and permission changes,
 * imports, views and downloads of documents...
 *
 * <p>Events are written in the caller's transaction: if the operation is rolled back, so is its
 * event. Nothing in the application updates an event; they are deleted after a year, every night.
 */
public interface AuditLog {

    void record(AuditEntry entry);

    /**
     * Records the entry, unless the same person did the same action on the same resource within
     * {@code within}: a page opened again, or reloaded, adds nothing.
     *
     * @return true when the entry was recorded
     */
    boolean recordOnce(AuditEntry entry, Duration within);

    /** The resources a person acted on last, most recent first, one line per resource. */
    List<Recent> recent(UUID organizationId, UUID actorId, String action, int limit);

    /** @param resourceId as recorded: the identifier of a document, for example */
    record Recent(String resourceId, Instant at) {
    }
}
