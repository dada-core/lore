package com.dadacore.lore.access;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.shared.ApiException;
import com.dadacore.lore.shared.SqlCondition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The rights of a person in the spaces, recalculated on every request.
 *
 * <ul>
 *   <li>Owners and administrators of the organization act as maintainers of every space, without
 *       being members.</li>
 *   <li>The other members see the spaces they belong to, with the role given there.</li>
 *   <li>An archived space is only seen by its maintainers, and by owners and administrators.</li>
 * </ul>
 */
@Service
public class SpaceAccess {

    private final JdbcClient jdbc;

    public SpaceAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The role of the person in the space, empty when they cannot see it. */
    public Optional<SpaceRole> role(Viewer viewer, SpaceRef space) {
        if (!space.organizationId().equals(viewer.organizationId())) {
            return Optional.empty();
        }
        if (viewer.isAdmin()) {
            return Optional.of(SpaceRole.MAINTAINER);
        }
        return jdbc.sql("select role from space_member where space_id = ? and user_id = ?")
                .params(space.id(), viewer.userId())
                .query((rs, row) -> SpaceRole.valueOf(rs.getString("role")))
                .optional()
                .filter(role -> !space.archived() || role == SpaceRole.MAINTAINER);
    }

    /**
     * The role of the person in the space, when it includes {@code minimum}: {@code 404} when they
     * cannot see the space, {@code 403 maintainer_only} or {@code 403 contributor_only} when they
     * can but their role is not enough.
     */
    public SpaceRole require(Viewer viewer, SpaceRef space, SpaceRole minimum) {
        SpaceRole role = role(viewer, space).orElseThrow(ApiException::notFound);
        if (!role.includes(minimum)) {
            throw ApiException.forbidden(minimum == SpaceRole.MAINTAINER ? "maintainer_only" : "contributor_only");
        }
        return role;
    }

    /**
     * The spaces the person can see, as a condition on the space table of a query.
     *
     * <p>The caller limits its query to the organization of the person, then adds this condition:
     * {@code where s.organization_id = ? and <condition>}.
     *
     * @param idColumn         the identifier column of the space table, for example {@code s.id}
     * @param archivedAtColumn its archiving date column, for example {@code s.archived_at}
     */
    public SqlCondition visibleSpaces(Viewer viewer, String idColumn, String archivedAtColumn) {
        if (viewer.isAdmin()) {
            return SqlCondition.always();
        }
        return new SqlCondition("exists (select 1 from space_member sm where sm.space_id = " + idColumn
                + " and sm.user_id = ? and (" + archivedAtColumn + " is null or sm.role = 'MAINTAINER'))",
                List.of(viewer.userId()));
    }

    /** The roles of the person in spaces they can see, for a list. */
    public Map<UUID, SpaceRole> roles(Viewer viewer, Collection<UUID> spaceIds) {
        Map<UUID, SpaceRole> roles = new HashMap<>();
        if (spaceIds.isEmpty()) {
            return roles;
        }
        if (viewer.isAdmin()) {
            spaceIds.forEach(id -> roles.put(id, SpaceRole.MAINTAINER));
            return roles;
        }
        jdbc.sql("select space_id, role from space_member where user_id = :user and space_id in (:spaces)")
                .param("user", viewer.userId())
                .param("spaces", spaceIds)
                .query(rs -> {
                    roles.put(rs.getObject("space_id", UUID.class), SpaceRole.valueOf(rs.getString("role")));
                });
        return roles;
    }
}
