package com.dadacore.lore.library.spaces;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.dadacore.lore.shared.SqlCondition;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class SpaceRepository {

    /** The columns read by {@link #space}, on a space table named {@code s}. */
    static final String FIELDS = """
            s.id, s.organization_id, s.name, s.slug, s.description, s.color, s.created_by,
                s.created_at, s.updated_at, s.archived_at
            """;
    private static final String COLUMNS = "select " + FIELDS + " from space s";

    private final JdbcClient jdbc;

    SpaceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Space> find(UUID organizationId, UUID id) {
        return jdbc.sql(COLUMNS + " where s.organization_id = ? and s.id = ?")
                .params(organizationId, id)
                .query(SpaceRepository::space)
                .optional();
    }

    /** As {@link #find}, the space locked until the end of the transaction. */
    Optional<Space> lock(UUID organizationId, UUID id) {
        return jdbc.sql(COLUMNS + " where s.organization_id = ? and s.id = ? for update")
                .params(organizationId, id)
                .query(SpaceRepository::space)
                .optional();
    }

    Optional<Space> findBySlug(UUID organizationId, String slug) {
        return jdbc.sql(COLUMNS + " where s.organization_id = ? and s.slug = ?")
                .params(organizationId, slug)
                .query(SpaceRepository::space)
                .optional();
    }

    /** The active or the archived spaces matching the condition, by name. */
    List<Space> list(UUID organizationId, boolean archived, SqlCondition visible) {
        List<Object> params = new ArrayList<>();
        params.add(organizationId);
        params.add(archived);
        params.addAll(visible.params());
        return jdbc.sql(COLUMNS + " where s.organization_id = ? and (s.archived_at is not null) = ? and ("
                        + visible.sql() + ") order by lower(unaccent(s.name)), s.id")
                .params(params)
                .query(SpaceRepository::space)
                .list();
    }

    boolean slugTaken(UUID organizationId, String slug, @Nullable UUID except) {
        return jdbc.sql("select exists (select 1 from space where organization_id = ? and slug = ? and id is distinct from ?)")
                .params(organizationId, slug, except)
                .query(Boolean.class)
                .single();
    }

    /** The active spaces matching the condition. */
    int countActive(UUID organizationId, SqlCondition visible) {
        List<Object> params = new ArrayList<>();
        params.add(organizationId);
        params.addAll(visible.params());
        return jdbc.sql("select count(*) from space s where s.organization_id = ? and s.archived_at is null and ("
                        + visible.sql() + ")")
                .params(params)
                .query(Integer.class)
                .single();
    }

    /** The new space, or nothing when its address is already used in the organization. */
    Optional<Space> insert(UUID organizationId, String name, String slug, @Nullable String description,
            String color, UUID createdBy) {
        return jdbc.sql("""
                insert into space as s (organization_id, name, slug, description, color, created_by)
                values (?, ?, ?, ?, ?, ?)
                on conflict (organization_id, slug) do nothing
                """ + " returning " + FIELDS)
                .params(organizationId, name, slug, description, color, createdBy)
                .query(SpaceRepository::space)
                .optional();
    }

    void update(UUID id, String name, String slug, @Nullable String description, String color) {
        jdbc.sql("update space set name = ?, slug = ?, description = ?, color = ?, updated_at = now() where id = ?")
                .params(name, slug, description, color, id)
                .update();
    }

    void archive(UUID id, boolean archived) {
        jdbc.sql("update space set archived_at = case when ? then now() end, updated_at = now() where id = ?")
                .params(archived, id)
                .update();
    }

    void delete(UUID id) {
        jdbc.sql("delete from space where id = ?").param(id).update();
    }

    void touch(UUID id) {
        jdbc.sql("update space set updated_at = now() where id = ?").param(id).update();
    }

    static Space space(ResultSet rs, int row) throws SQLException {
        Timestamp archivedAt = rs.getTimestamp("archived_at");
        return new Space(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getString("name"),
                rs.getString("slug"),
                rs.getString("description"),
                rs.getString("color"),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                archivedAt == null ? null : archivedAt.toInstant());
    }
}
