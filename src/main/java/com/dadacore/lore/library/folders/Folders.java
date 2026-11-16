package com.dadacore.lore.library.folders;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.dadacore.lore.access.SpaceRole;
import com.dadacore.lore.audit.AuditEntry;
import com.dadacore.lore.audit.AuditLog;
import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.library.spaces.Space;
import com.dadacore.lore.library.spaces.Spaces;
import com.dadacore.lore.shared.ApiException;
import com.dadacore.lore.shared.Checks;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The folders of a space: one level, in the order of their creation, with names unique in the
 * space whatever their case. Contributors create, rename and delete them.
 */
@Service
class Folders {

    private static final int MAX_NAME_LENGTH = 80;

    private final JdbcClient jdbc;
    private final Spaces spaces;
    private final AuditLog audit;

    Folders(JdbcClient jdbc, Spaces spaces, AuditLog audit) {
        this.jdbc = jdbc;
        this.spaces = spaces;
        this.audit = audit;
    }

    record FolderView(UUID id, String name, Instant createdAt) {
    }

    List<FolderView> list(Viewer viewer, UUID spaceId) {
        spaces.open(viewer, spaceId, SpaceRole.READER);
        return jdbc.sql("select id, name, created_at from folder where space_id = ? order by created_at, id")
                .param(spaceId)
                .query(Folders::folder)
                .list();
    }

    /** Field error {@code name:name_taken}; {@code 403 contributor_only}, {@code 409 space_archived}. */
    @Transactional
    FolderView create(Viewer viewer, UUID spaceId, String requestedName) {
        Space space = spaces.openForChange(viewer, spaceId, SpaceRole.CONTRIBUTOR).requireActive().space();
        String name = name(requestedName);
        Optional<FolderView> created = jdbc.sql("""
                insert into folder (space_id, name) values (?, ?)
                on conflict do nothing
                returning id, name, created_at
                """)
                .params(space.id(), name)
                .query(Folders::folder)
                .optional();
        FolderView folder = created.orElseThrow(Folders::nameTaken);
        spaces.touch(space.id());
        audit.record(entry(viewer, space, "folder.created", folder.id()).detail("name", name).build());
        return folder;
    }

    /** Field error {@code name:name_taken}. */
    @Transactional
    FolderView rename(Viewer viewer, UUID spaceId, UUID folderId, String requestedName) {
        Space space = spaces.openForChange(viewer, spaceId, SpaceRole.CONTRIBUTOR).requireActive().space();
        FolderView folder = find(space.id(), folderId).orElseThrow(ApiException::notFound);
        String name = name(requestedName);
        if (name.equals(folder.name())) {
            return folder;
        }
        boolean taken = jdbc.sql("select exists (select 1 from folder where space_id = ? and lower(name) = lower(?) and id <> ?)")
                .params(space.id(), name, folderId)
                .query(Boolean.class)
                .single();
        if (taken) {
            throw nameTaken();
        }
        try {
            jdbc.sql("update folder set name = ? where id = ?").params(name, folderId).update();
        } catch (DuplicateKeyException e) {
            throw nameTaken();
        }
        spaces.touch(space.id());
        audit.record(entry(viewer, space, "folder.renamed", folderId)
                .detail("name", name)
                .detail("previousName", folder.name())
                .build());
        return new FolderView(folderId, name, folder.createdAt());
    }

    /**
     * Deletes an empty folder: one that holds documents, even some the person cannot see, answers
     * {@code 409 folder_not_empty} (a known limit: it tells that the folder is not empty, nothing more).
     */
    @Transactional
    void delete(Viewer viewer, UUID spaceId, UUID folderId) {
        Space space = spaces.openForChange(viewer, spaceId, SpaceRole.CONTRIBUTOR).requireActive().space();
        FolderView folder = find(space.id(), folderId).orElseThrow(ApiException::notFound);
        boolean empty = !jdbc.sql("select exists (select 1 from document where folder_id = ?)")
                .param(folderId)
                .query(Boolean.class)
                .single();
        if (!empty) {
            throw ApiException.conflict("folder_not_empty");
        }
        jdbc.sql("delete from folder where id = ?").param(folderId).update();
        spaces.touch(space.id());
        audit.record(entry(viewer, space, "folder.deleted", folderId).detail("name", folder.name()).build());
    }

    private Optional<FolderView> find(UUID spaceId, UUID folderId) {
        return jdbc.sql("select id, name, created_at from folder where id = ? and space_id = ?")
                .params(folderId, spaceId)
                .query(Folders::folder)
                .optional();
    }

    private static String name(String value) {
        Checks checks = Checks.start();
        String name = checks.text("name", value, MAX_NAME_LENGTH);
        checks.orThrow();
        return name;
    }

    private static ApiException nameTaken() {
        return Checks.rejected("name", "name_taken");
    }

    private static AuditEntry.Builder entry(Viewer viewer, Space space, String action, UUID folderId) {
        return AuditEntry.of(space.organizationId(), viewer.userId(), action)
                .resource("folder", folderId)
                .ip(viewer.ip())
                .detail("space", space.name())
                .detail("spaceId", space.id());
    }

    private static FolderView folder(ResultSet rs, int row) throws SQLException {
        return new FolderView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getTimestamp("created_at").toInstant());
    }
}
