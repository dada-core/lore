package com.dadacore.lore.access;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Collator;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.dadacore.lore.audit.AuditEntry;
import com.dadacore.lore.audit.AuditLog;
import com.dadacore.lore.identity.OrganizationMember;
import com.dadacore.lore.identity.OrganizationMembers;
import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.shared.ApiException;
import com.dadacore.lore.shared.Checks;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The members of a space and their roles.
 *
 * <p>Everyone who sees a space sees its members. Its maintainers, and the owners and administrators
 * of the organization, add members of the organization, change their roles and remove them; anyone
 * can leave a space. A space always keeps a maintainer, and an archived space can no longer be
 * changed.
 *
 * <p>Disabling the account of the last maintainer, or removing that person from the organization,
 * stays possible: the owners and administrators, who manage every space, then name another
 * maintainer. Blocking it would hold back a security measure for the sake of a space.
 */
@Service
public class SpaceMembers {

    private static final int CANDIDATES = 20;

    private final JdbcClient jdbc;
    private final SpaceAccess access;
    private final OrganizationMembers people;
    private final AuditLog audit;

    public SpaceMembers(JdbcClient jdbc, SpaceAccess access, OrganizationMembers people, AuditLog audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.people = people;
        this.audit = audit;
    }

    /**
     * @param active false when the account is disabled
     * @param self   true for the person making the request
     */
    public record SpaceMemberView(UUID id, String displayName, String email, String role, boolean active,
            boolean self, Instant addedAt) {
    }

    /** A member of the organization who can be added to the space. */
    public record Candidate(UUID id, String displayName, String email) {
    }

    private record Row(UUID userId, SpaceRole role, Instant addedAt) {
    }

    /** The members by name, ignoring case and accents. */
    public List<SpaceMemberView> list(Viewer viewer, SpaceRef space) {
        access.require(viewer, space, SpaceRole.READER);
        List<Row> rows = rows(space.id());
        Map<UUID, OrganizationMember> found = people.find(space.organizationId(),
                rows.stream().map(Row::userId).toList());
        Collator names = Collator.getInstance(Locale.FRENCH);
        names.setStrength(Collator.PRIMARY);
        return rows.stream()
                .filter(row -> found.containsKey(row.userId()))
                .map(row -> view(viewer, found.get(row.userId()), row.role(), row.addedAt()))
                .sorted(Comparator.comparing(SpaceMemberView::displayName, names)
                        .thenComparing(SpaceMemberView::id))
                .toList();
    }

    /** Active members of the organization who are not in the space yet, by name. */
    public List<Candidate> candidates(Viewer viewer, SpaceRef space, @Nullable String search) {
        access.require(viewer, space, SpaceRole.MAINTAINER);
        List<UUID> members = rows(space.id()).stream().map(Row::userId).toList();
        return people.search(space.organizationId(), search, members, CANDIDATES).stream()
                .map(person -> new Candidate(person.id(), person.displayName(), person.email()))
                .toList();
    }

    /** The number of members of each space; a space without members is missing. */
    public Map<UUID, Integer> counts(Collection<UUID> spaceIds) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (spaceIds.isEmpty()) {
            return counts;
        }
        jdbc.sql("select space_id, count(*) as members from space_member where space_id in (:spaces) group by space_id")
                .param("spaces", spaceIds)
                .query(rs -> {
                    counts.put(rs.getObject("space_id", UUID.class), rs.getInt("members"));
                });
        return counts;
    }

    /** The role of a person in the space, empty when they are not a member of it. */
    public Optional<SpaceRole> roleOf(SpaceRef space, UUID userId) {
        return row(space.id(), userId).map(Row::role);
    }

    /** Field errors {@code userId:unknown_member}; {@code 409 already_space_member}. */
    @Transactional
    public SpaceMemberView add(Viewer viewer, SpaceRef space, @Nullable UUID userId, @Nullable String requestedRole) {
        access.require(viewer, space, SpaceRole.MAINTAINER);
        space.requireActive();
        Checks checks = Checks.start();
        SpaceRole role = checks.oneOf("role", requestedRole, SpaceRole.class);
        OrganizationMember person = null;
        if (userId == null) {
            checks.fail("userId", "required");
        } else {
            person = people.find(space.organizationId(), userId).filter(OrganizationMember::active).orElse(null);
            if (person == null) {
                checks.fail("userId", "unknown_member");
            }
        }
        checks.orThrow();
        if (!grant(space, userId, role, viewer.userId())) {
            throw ApiException.conflict("already_space_member");
        }
        audit.record(entry(viewer, space, "space.member_added", person)
                .detail("role", role.name())
                .build());
        Row row = row(space.id(), userId).orElseThrow();
        return view(viewer, person, row.role(), row.addedAt());
    }

    /** {@code 409 last_maintainer}. */
    @Transactional
    public SpaceMemberView changeRole(Viewer viewer, SpaceRef space, UUID userId, @Nullable String requestedRole) {
        access.require(viewer, space, SpaceRole.MAINTAINER);
        space.requireActive();
        Checks checks = Checks.start();
        SpaceRole role = checks.oneOf("role", requestedRole, SpaceRole.class);
        checks.orThrow();
        Row current = row(space.id(), userId).orElseThrow(ApiException::notFound);
        OrganizationMember person = people.find(space.organizationId(), userId).orElseThrow(ApiException::notFound);
        if (role != current.role()) {
            if (current.role() == SpaceRole.MAINTAINER) {
                requireAnotherMaintainer(space, userId);
            }
            jdbc.sql("update space_member set role = ? where space_id = ? and user_id = ?")
                    .params(role.name(), space.id(), userId)
                    .update();
            audit.record(entry(viewer, space, "space.member_role_changed", person)
                    .detail("before", current.role().name())
                    .detail("after", role.name())
                    .build());
        }
        return view(viewer, person, role, current.addedAt());
    }

    /** Removes a member, or lets someone leave the space. {@code 409 last_maintainer}. */
    @Transactional
    public void remove(Viewer viewer, SpaceRef space, UUID userId) {
        boolean leaving = userId.equals(viewer.userId());
        access.require(viewer, space, leaving ? SpaceRole.READER : SpaceRole.MAINTAINER);
        space.requireActive();
        Row current = row(space.id(), userId).orElseThrow(ApiException::notFound);
        if (current.role() == SpaceRole.MAINTAINER) {
            requireAnotherMaintainer(space, userId);
        }
        jdbc.sql("delete from space_member where space_id = ? and user_id = ?")
                .params(space.id(), userId)
                .update();
        Optional<OrganizationMember> person = people.find(space.organizationId(), userId);
        audit.record(AuditEntry.of(space.organizationId(), viewer.userId(), "space.member_removed")
                .resource("space", space.id())
                .ip(viewer.ip())
                .detail("space", space.name())
                .detail("memberId", userId)
                .detail("name", person.map(OrganizationMember::displayName).orElse(null))
                .detail("role", current.role().name())
                .detail("left", leaving ? true : null)
                .build());
    }

    /** The person who creates a space becomes its maintainer. */
    public void addCreator(SpaceRef space, UUID userId) {
        grant(space, userId, SpaceRole.MAINTAINER, userId);
    }

    /**
     * Gives a role to someone, for example on arriving with an invitation; a role they already
     * have stays.
     *
     * @return false when they were already a member of the space
     */
    public boolean grant(SpaceRef space, UUID userId, SpaceRole role, @Nullable UUID addedBy) {
        return jdbc.sql("""
                insert into space_member (space_id, user_id, organization_id, role, added_by)
                values (?, ?, ?, ?, ?) on conflict do nothing
                """)
                .params(space.id(), userId, space.organizationId(), role.name(), addedBy)
                .update() > 0;
    }

    /**
     * Before {@code userId} stops being a maintainer: another maintainer who can sign in must remain.
     * The maintainers are locked until the end of the transaction, so that two of them cannot
     * withdraw each other at the same time; a disabled account does not count.
     */
    private void requireAnotherMaintainer(SpaceRef space, UUID userId) {
        List<UUID> others = jdbc.sql("select user_id from space_member where space_id = ? and role = 'MAINTAINER' for update")
                .param(space.id())
                .query(UUID.class)
                .list()
                .stream()
                .filter(id -> !id.equals(userId))
                .toList();
        boolean anotherActive = people.find(space.organizationId(), others).values().stream()
                .anyMatch(OrganizationMember::active);
        if (!anotherActive) {
            throw ApiException.conflict("last_maintainer");
        }
    }

    private List<Row> rows(UUID spaceId) {
        return jdbc.sql("select user_id, role, created_at from space_member where space_id = ?")
                .param(spaceId)
                .query(SpaceMembers::toRow)
                .list();
    }

    private Optional<Row> row(UUID spaceId, UUID userId) {
        return jdbc.sql("select user_id, role, created_at from space_member where space_id = ? and user_id = ?")
                .params(spaceId, userId)
                .query(SpaceMembers::toRow)
                .optional();
    }

    private static Row toRow(ResultSet rs, int row) throws SQLException {
        return new Row(rs.getObject("user_id", UUID.class), SpaceRole.valueOf(rs.getString("role")),
                rs.getTimestamp("created_at").toInstant());
    }

    private static AuditEntry.Builder entry(Viewer viewer, SpaceRef space, String action, OrganizationMember person) {
        return AuditEntry.of(space.organizationId(), viewer.userId(), action)
                .resource("space", space.id())
                .ip(viewer.ip())
                .detail("space", space.name())
                .detail("memberId", person.id())
                .detail("name", person.displayName());
    }

    private static SpaceMemberView view(Viewer viewer, OrganizationMember person, SpaceRole role, Instant addedAt) {
        return new SpaceMemberView(person.id(), person.displayName(), person.email(), role.name(), person.active(),
                person.id().equals(viewer.userId()), addedAt);
    }
}
