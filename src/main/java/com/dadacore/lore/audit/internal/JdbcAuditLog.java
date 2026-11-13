package com.dadacore.lore.audit.internal;

import com.dadacore.lore.audit.AuditEntry;
import com.dadacore.lore.audit.AuditLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class JdbcAuditLog implements AuditLog {

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
}
