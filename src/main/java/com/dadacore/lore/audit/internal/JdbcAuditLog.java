package com.dadacore.lore.audit.internal;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.dadacore.lore.audit.AuditEntry;
import com.dadacore.lore.audit.AuditLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class JdbcAuditLog implements AuditLog {

    /**
     * The name of an action, written in the query rather than passed as a parameter: PostgreSQL then
     * uses the partial index of the views ({@code where action = 'document.viewed'}).
     */
    private static final Pattern ACTION = Pattern.compile("[a-z_]+\\.[a-z_]+");

    private final JdbcClient jdbc;
    private final JsonMapper json;

    JdbcAuditLog(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void record(AuditEntry entry) {
        jdbc.sql("""
                insert into audit_event
                    (organization_id, actor_id, action, resource_type, resource_id, ip, details)
                values (?, ?, ?, ?, ?, ?, ?::jsonb)
                """)
                .params(entry.organizationId(), entry.actorId(), entry.action(),
                        entry.resourceType(), entry.resourceId(), entry.ip(),
                        json.writeValueAsString(entry.details()))
                .update();
    }

    @Override
    public boolean recordOnce(AuditEntry entry, Duration within) {
        String action = action(entry.action());
        return jdbc.sql("""
                insert into audit_event
                    (organization_id, actor_id, action, resource_type, resource_id, ip, details)
                select ?, ?, ?, ?, ?, ?, ?::jsonb
                where not exists (
                    select 1 from audit_event
                    where organization_id = ? and actor_id = ? and action = '%s' and resource_id = ?
                      and occurred_at > now() - ? * interval '1 second')
                """.formatted(action))
                .params(entry.organizationId(), entry.actorId(), action, entry.resourceType(), entry.resourceId(),
                        entry.ip(), json.writeValueAsString(entry.details()),
                        entry.organizationId(), entry.actorId(), entry.resourceId(),
                        within.toSeconds())
                .update() > 0;
    }

    @Override
    public List<Recent> recent(UUID organizationId, UUID actorId, String action, int limit) {
        return jdbc.sql("""
                select resource_id, max(occurred_at) as at from audit_event
                where organization_id = ? and actor_id = ? and action = '%s' and resource_id is not null
                group by resource_id order by at desc limit ?
                """.formatted(action(action)))
                .params(organizationId, actorId, limit)
                .query((rs, row) -> new Recent(rs.getString("resource_id"), rs.getTimestamp("at").toInstant()))
                .list();
    }

    private static String action(String action) {
        if (!ACTION.matcher(action).matches()) {
            throw new IllegalArgumentException("Not an action: " + action);
        }
        return action;
    }
}
