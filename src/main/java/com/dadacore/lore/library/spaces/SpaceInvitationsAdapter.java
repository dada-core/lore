package com.dadacore.lore.library.spaces;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.dadacore.lore.access.SpaceAccess;
import com.dadacore.lore.access.SpaceMembers;
import com.dadacore.lore.access.SpaceRole;
import com.dadacore.lore.identity.InvitationSpaces;
import com.dadacore.lore.identity.InvitedSpace;
import com.dadacore.lore.identity.SpaceInvitation;
import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.shared.Checks;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * "Spaces to join" in an invitation to the organization: the person creating it can only choose
 * active spaces they manage, which an administrator does for every space. A space archived or
 * deleted since then is neither announced nor given.
 */
@Component
class SpaceInvitationsAdapter implements InvitationSpaces {

    private final JdbcClient jdbc;
    private final SpaceRepository repository;
    private final SpaceAccess access;
    private final SpaceMembers members;

    SpaceInvitationsAdapter(JdbcClient jdbc, SpaceRepository repository, SpaceAccess access, SpaceMembers members) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.access = access;
        this.members = members;
    }

    @Override
    public List<InvitedSpace> attach(Viewer viewer, UUID invitationId, List<SpaceInvitation> requested) {
        Checks checks = Checks.start();
        Map<UUID, InvitedSpace> chosen = new LinkedHashMap<>();
        for (SpaceInvitation item : requested) {
            SpaceRole role = checks.oneOf("spaces", item == null ? null : item.role(), SpaceRole.class);
            Optional<Space> space = item == null || item.spaceId() == null
                    ? Optional.empty()
                    : repository.find(viewer.organizationId(), item.spaceId())
                            .filter(found -> access.role(viewer, found.ref())
                                    .filter(SpaceRole.MAINTAINER::equals).isPresent());
            if (space.isEmpty()) {
                checks.fail("spaces", "unknown_space");
            } else if (space.get().archived()) {
                checks.fail("spaces", "space_archived");
            } else if (role != null) {
                chosen.put(space.get().id(), invited(space.get(), role));
            }
        }
        checks.orThrow();
        chosen.values().forEach(space -> jdbc.sql(
                        "insert into invitation_space (invitation_id, space_id, role) values (?, ?, ?)")
                .params(invitationId, space.id(), space.role())
                .update());
        return List.copyOf(chosen.values());
    }

    @Override
    public Map<UUID, List<InvitedSpace>> spaces(Collection<UUID> invitationIds) {
        Map<UUID, List<InvitedSpace>> spaces = new LinkedHashMap<>();
        if (invitationIds.isEmpty()) {
            return spaces;
        }
        jdbc.sql("""
                select i.invitation_id, s.id, s.name, s.color, i.role
                from invitation_space i join space s on s.id = i.space_id
                where i.invitation_id in (:invitations) and s.archived_at is null
                order by lower(unaccent(s.name)), s.id
                """)
                .param("invitations", invitationIds)
                .query(rs -> {
                    spaces.computeIfAbsent(rs.getObject("invitation_id", UUID.class), id -> new ArrayList<>())
                            .add(new InvitedSpace(rs.getObject("id", UUID.class), rs.getString("name"),
                                    rs.getString("color"), rs.getString("role")));
                });
        return spaces;
    }

    @Override
    public List<InvitedSpace> join(UUID invitationId, UUID organizationId, UUID userId) {
        // The spaces are locked while they are joined: an archiving or a deletion under way is waited
        // for, and a space it archived or deleted is left out.
        List<Invited> invited = jdbc.sql("select " + SpaceRepository.FIELDS + """
                    , i.role as invited_role
                from invitation_space i join space s on s.id = i.space_id
                where i.invitation_id = ? and s.organization_id = ? and s.archived_at is null
                order by lower(unaccent(s.name)), s.id
                for share of s
                """)
                .params(invitationId, organizationId)
                .query((rs, row) -> new Invited(SpaceRepository.space(rs, row),
                        SpaceRole.valueOf(rs.getString("invited_role"))))
                .list();
        invited.forEach(item -> members.grant(item.space().ref(), userId, item.role(), null));
        return invited.stream().map(item -> invited(item.space(), item.role())).toList();
    }

    private record Invited(Space space, SpaceRole role) {
    }

    private static InvitedSpace invited(Space space, SpaceRole role) {
        return new InvitedSpace(space.id(), space.name(), space.color(), role.name());
    }
}
