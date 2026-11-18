package com.dadacore.lore.audit.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.dadacore.lore.audit.AuditCategories;
import com.dadacore.lore.audit.AuditEntry;
import com.dadacore.lore.audit.AuditLog;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
class JdbcAuditLog implements AuditLog {

    /**
     * The name of an action, written in the query rather than passed as a parameter: PostgreSQL then
     * uses the partial index of the views ({@code where action = 'document.viewed'}).
     */
    private static final Pattern ACTION = Pattern.compile("[a-z_]+\\.[a-z_]+");
    private static final TypeReference<Map<String, Object>> DETAILS = new TypeReference<>() {
    };
    private static final String EVENT_COLUMNS = "e.id, e.occurred_at, e.actor_id, e.action, e.resource_type, e.resource_id, e.ip,"
            + " e.details::text as details";

    /** The rows read at a time by {@link #eachEvent}. */
    static final int FETCH = 1000;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final JdbcTemplate streaming;
    private final TransactionTemplate readOnly;

    JdbcAuditLog(JdbcClient jdbc, JsonMapper json, DataSource dataSource, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.streaming = new JdbcTemplate(dataSource);
        this.streaming.setFetchSize(FETCH);
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
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

    @Override
    public List<Event> activity(UUID organizationId, String resourceType, String resourceId, @Nullable Cursor before,
            int limit) {
        List<Object> params = new ArrayList<>(List.of(organizationId, resourceType, resourceId));
        // Written as the partial index of the activity is: views and downloads left out.
        StringBuilder sql = new StringBuilder("select " + EVENT_COLUMNS + " from audit_event e"
                + " where e.organization_id = ? and e.resource_type = ? and e.resource_id = ?"
                + " and e.action not in ('document.viewed', 'document.downloaded')");
        after(before, sql, params);
        sql.append(" order by e.occurred_at desc, e.id desc limit ?");
        params.add(limit);
        return jdbc.sql(sql.toString()).params(params).query(this::event).list();
    }

    @Override
    public List<Event> documentEvents(UUID organizationId, int limit) {
        return jdbc.sql("select " + EVENT_COLUMNS + " from audit_event e where e.organization_id = ?"
                        + " and e.action in ('document.imported', 'document.version_imported', 'document.status_changed')"
                        + " order by e.occurred_at desc, e.id desc limit ?")
                .params(organizationId, limit)
                .query(this::event)
                .list();
    }

    @Override
    public List<Event> events(UUID organizationId, Filter filter, @Nullable Cursor before, int limit) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("select " + EVENT_COLUMNS + " from audit_event e");
        where(organizationId, filter, sql, params);
        after(before, sql, params);
        sql.append(" order by e.occurred_at desc, e.id desc limit ?");
        params.add(limit);
        return jdbc.sql(sql.toString()).params(params).query(this::event).list();
    }

    @Override
    public long eachEvent(UUID organizationId, Filter filter, Consumer<Event> each) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("select " + EVENT_COLUMNS + " from audit_event e");
        where(organizationId, filter, sql, params);
        sql.append(" order by e.occurred_at desc, e.id desc");
        long[] count = {0};
        // A cursor of PostgreSQL needs a transaction: the rows come a thousand at a time.
        readOnly.executeWithoutResult(status -> streaming.query(sql.toString(), statement -> {
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
        }, rs -> {
            each.accept(event(rs, 0));
            count[0]++;
        }));
        return count[0];
    }

    private static void where(UUID organizationId, Filter filter, StringBuilder sql, List<Object> params) {
        sql.append(" where e.organization_id = ?");
        params.add(organizationId);
        if (filter.category() != null) {
            sql.append(AuditCategories.OTHER.equals(filter.category()) ? " and not (e.action = any(?))" : " and e.action = any(?)");
            params.add(AuditCategories.actions(filter.category()).toArray(String[]::new));
        }
        if (filter.system()) {
            sql.append(" and e.actor_id is null");
        } else if (filter.actorId() != null) {
            sql.append(" and e.actor_id = ?");
            params.add(filter.actorId());
        }
        if (filter.from() != null) {
            sql.append(" and e.occurred_at >= ?");
            params.add(java.sql.Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" and e.occurred_at < ?");
            params.add(java.sql.Timestamp.from(filter.to()));
        }
    }

    /**
     * The events after a cursor, in the order of the pages: written so that the index bounds the
     * reading (a single {@code or} would make it read from the start).
     */
    private static void after(@Nullable Cursor before, StringBuilder sql, List<Object> params) {
        if (before != null) {
            java.sql.Timestamp at = java.sql.Timestamp.from(before.occurredAt());
            sql.append(" and e.occurred_at <= ? and (e.occurred_at < ? or e.id < ?)");
            params.add(at);
            params.add(at);
            params.add(before.id());
        }
    }

    private Event event(ResultSet rs, int row) throws SQLException {
        return new Event(rs.getLong("id"), rs.getTimestamp("occurred_at").toInstant(), rs.getObject("actor_id", UUID.class),
                rs.getString("action"), rs.getString("resource_type"), rs.getString("resource_id"), rs.getString("ip"),
                json.readValue(rs.getString("details"), DETAILS));
    }

    private static String action(String action) {
        if (!ACTION.matcher(action).matches()) {
            throw new IllegalArgumentException("Not an action: " + action);
        }
        return action;
    }
}
