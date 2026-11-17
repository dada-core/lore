package com.dadacore.lore.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import javax.sql.DataSource;

import com.dadacore.lore.support.ApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The events of the audit log are kept a year, then deleted by one instance at a time. */
class AuditRetentionTests extends ApiTest {

    @Autowired
    AuditRetention retention;

    @Autowired
    DataSource dataSource;

    @Test
    void theEventsOlderThanAYearAreDeletedAndOnlyThem() {
        UUID organization = organization();
        event(organization, "366 days");
        event(organization, "400 days");
        event(organization, "364 days");
        event(organization, "1 minute");

        assertThat(retention.purge()).isEqualTo(2);

        assertThat(jdbc.sql("select count(*) from audit_event").query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from audit_event where occurred_at < now() - interval '1 year'")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void anotherInstanceThatPurgesAlreadyLeavesTheWorkToIt() throws Exception {
        UUID organization = organization();
        event(organization, "400 days");

        try (Connection other = dataSource.getConnection()) {
            try (PreparedStatement lock = other.prepareStatement("select pg_advisory_lock(?)")) {
                lock.setLong(1, AuditRetention.LOCK);
                lock.execute();
            }
            assertThat(retention.purge()).isEqualTo(-1);
            assertThat(jdbc.sql("select count(*) from audit_event").query(Integer.class).single()).isEqualTo(1);
            try (PreparedStatement unlock = other.prepareStatement("select pg_advisory_unlock(?)")) {
                unlock.setLong(1, AuditRetention.LOCK);
                unlock.execute();
            }
        }

        assertThat(retention.purge()).isEqualTo(1);
    }

    private UUID organization() {
        return jdbc.sql("insert into organization (name, slug) values ('DadaGroup', 'dadagroup') returning id")
                .query(UUID.class).single();
    }

    private void event(UUID organization, String age) {
        jdbc.sql("insert into audit_event (organization_id, action, occurred_at) values (?, 'document.viewed', now() - cast(? as interval))")
                .params(organization, age)
                .update();
    }
}
