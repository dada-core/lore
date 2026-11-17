package com.dadacore.lore.audit.internal;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The events of the audit log are kept a year: every night, those older are deleted, organization
 * by organization, in batches, so that the table is never locked for long. Several instances of
 * Lore share the database: an advisory lock lets one of them purge, the others skip that night.
 */
@Component
@ConditionalOnWebApplication
class AuditRetention {

    static final int BATCH = 10_000;

    /** The key of the advisory lock that one instance holds while it purges. */
    static final long LOCK = 0x4c6f72654175646dL;

    private static final Logger log = LoggerFactory.getLogger(AuditRetention.class);

    private final JdbcTemplate template;

    AuditRetention(JdbcTemplate template) {
        this.template = template;
    }

    @Scheduled(cron = "${lore.audit.purge-cron:0 17 3 * * *}")
    void nightly() {
        try {
            int deleted = purge();
            if (deleted > 0) {
                log.info("Audit log: {} events older than a year deleted", deleted);
            }
        } catch (RuntimeException e) {
            // Tried again the next night; the log keeps working meanwhile.
            log.warn("Audit log: the purge of the old events failed: {}", e.toString());
        }
    }

    /** Deletes the events older than a year; -1 when another instance is purging. */
    int purge() {
        return template.execute((ConnectionCallback<Integer>) connection -> {
            try (PreparedStatement lock = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
                lock.setLong(1, LOCK);
                try (ResultSet locked = lock.executeQuery()) {
                    if (!locked.next() || !locked.getBoolean(1)) {
                        return -1;
                    }
                }
            }
            try {
                List<UUID> organizations = new ArrayList<>();
                try (PreparedStatement select = connection.prepareStatement("select id from organization order by id");
                        ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        organizations.add(rows.getObject(1, UUID.class));
                    }
                }
                int total = 0;
                try (PreparedStatement delete = connection.prepareStatement("""
                        delete from audit_event where id in (
                            select id from audit_event
                            where organization_id = ? and occurred_at < now() - interval '1 year'
                            limit ?)
                        """)) {
                    for (UUID organization : organizations) {
                        int deleted;
                        do {
                            delete.setObject(1, organization);
                            delete.setInt(2, BATCH);
                            deleted = delete.executeUpdate();
                            total += deleted;
                        } while (deleted == BATCH);
                    }
                }
                return total;
            } finally {
                try (PreparedStatement unlock = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                    unlock.setLong(1, LOCK);
                    unlock.execute();
                }
            }
        });
    }
}
