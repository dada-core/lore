package com.dadacore.lore.access;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.dadacore.lore.shared.Checks;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The people chosen for a restricted document. Only members of its space can be chosen; leaving
 * the space, or the organization, removes them by the database.
 */
@Service
public class DocumentGrants {

    private final JdbcClient jdbc;

    public DocumentGrants(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The people added and removed by a change. */
    public record Change(List<UUID> added, List<UUID> removed) {

        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty();
        }
    }

    /** The people chosen for the document, in the order they were chosen. */
    public List<UUID> chosen(UUID documentId) {
        return jdbc.sql("select user_id from document_grant where document_id = ? order by created_at, user_id")
                .param(documentId)
                .query(UUID.class)
                .list();
    }

    /** Field error {@code userIds:not_space_member} when someone chosen is not in the space. */
    public void check(SpaceRef space, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        Set<UUID> members = new LinkedHashSet<>(jdbc.sql("select user_id from space_member where space_id = :space and user_id in (:users)")
                .param("space", space.id())
                .param("users", userIds)
                .query(UUID.class)
                .list());
        if (!members.containsAll(userIds)) {
            throw Checks.rejected("userIds", "not_space_member");
        }
    }

    /**
     * Gives exactly these people access to the document, inside the transaction of the change
     * (or of the import): the document is never visible to the others, not even for a moment.
     */
    public Change replace(UUID documentId, SpaceRef space, Collection<UUID> userIds, @Nullable UUID grantedBy) {
        check(space, userIds);
        List<UUID> before = chosen(documentId);
        Set<UUID> after = new LinkedHashSet<>(userIds);
        List<UUID> removed = new ArrayList<>(before);
        removed.removeAll(after);
        List<UUID> added = new ArrayList<>(after);
        added.removeAll(before);
        if (!removed.isEmpty()) {
            jdbc.sql("delete from document_grant where document_id = :document and user_id in (:users)")
                    .param("document", documentId)
                    .param("users", removed)
                    .update();
        }
        for (UUID userId : added) {
            jdbc.sql("""
                    insert into document_grant (document_id, space_id, user_id, granted_by) values (?, ?, ?, ?)
                    on conflict do nothing
                    """)
                    .params(documentId, space.id(), userId, grantedBy)
                    .update();
        }
        return new Change(List.copyOf(added), List.copyOf(removed));
    }

    /** The members of the space who are its maintainers, who always see its restricted documents. */
    public List<UUID> maintainers(SpaceRef space) {
        return jdbc.sql("select user_id from space_member where space_id = ? and role = 'MAINTAINER' order by created_at")
                .param(space.id())
                .query(UUID.class)
                .list();
    }
}
