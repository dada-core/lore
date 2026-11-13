package com.dadacore.lore.audit;

/**
 * The audit log of sensitive operations: sign-in failures, account and permission changes,
 * imports, downloads...
 *
 * <p>Events are written in the caller's transaction: if the operation is rolled back, so is its
 * event. Nothing in the application updates or deletes an event.
 */
public interface AuditLog {

    void record(AuditEntry entry);
}
