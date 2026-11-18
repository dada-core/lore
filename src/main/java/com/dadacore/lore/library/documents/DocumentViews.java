package com.dadacore.lore.library.documents;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.dadacore.lore.identity.OrganizationMember;
import com.dadacore.lore.identity.OrganizationMembers;
import com.dadacore.lore.library.spaces.Space;
import com.dadacore.lore.storage.FileStore;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The documents as the API shows them, with the names of the people and of the folder. */
@Component
class DocumentViews {

    /**
     * A document, in full: the page of a document.
     *
     * @param statusChangedAt since when it has its status
     * @param replacedBy      for an obsolete document, the one that replaces it; null when there is
     *                        none, or when the person may not read it
     * @param newerVersion    a version imported after the current one, still extracted or failed;
     *                        null when the latest version is the current one
     */
    record DocumentView(UUID id, String title, @Nullable String description, @Nullable String category, String status,
            Instant statusChangedAt, @Nullable DocumentLink replacedBy, boolean restricted, SpaceLink space,
            @Nullable FolderLink folder, @Nullable PersonLink owner, Instant createdAt, Instant updatedAt,
            int versionCount, VersionView currentVersion, @Nullable NewerVersion newerVersion, DocumentActions actions,
            @Nullable DocumentLink duplicateOf) {
    }

    /**
     * A document in a list.
     *
     * @param newerVersion a version imported after the current one, still extracted or failed
     */
    record DocumentItem(UUID id, String title, @Nullable UUID folderId, @Nullable String category, String status,
            boolean restricted, @Nullable PersonLink owner, Instant updatedAt, int version, String filename,
            String mediaType, long sizeBytes, String extractionStatus, @Nullable String extractionError,
            @Nullable String language, @Nullable Integer pageCount, @Nullable Integer sectionCount,
            @Nullable NewerVersion newerVersion) {
    }

    /**
     * A version imported after the current one: in progress ({@code PENDING}), or failed.
     *
     * @param author null in a list
     */
    record NewerVersion(int number, String extractionStatus, @Nullable String extractionError, Instant createdAt,
            @Nullable PersonLink author) {
    }

    /**
     * @param extractionStatus {@code PENDING}, {@code INDEXED}, {@code SCANNED} or {@code FAILED}
     * @param extractionError  for a failure: {@code encrypted}, {@code corrupt}, {@code unsupported_content},
     *                         {@code timeout}, {@code extractor_crashed} or {@code file_missing}
     * @param textTruncated    the text went beyond what is kept, and was cut
     * @param sha256           the fingerprint of the file, to compare it with a copy
     * @param fileAvailable    false when the file is missing on the disk of the server
     * @param note             what changes, written at the import of the version; none for the first
     * @param changeRatio      the share of passages changed from the version before, from 0 to 1;
     *                         null when unknown (a scan, a failure, a version never current)
     */
    record VersionView(UUID id, int number, String filename, String mediaType, long sizeBytes,
            @Nullable String language, @Nullable Integer pageCount, @Nullable Integer sectionCount,
            String extractionStatus, @Nullable String extractionError, boolean textTruncated,
            @Nullable Instant extractedAt, Instant createdAt, @Nullable PersonLink author, String sha256,
            boolean fileAvailable, @Nullable String note, @Nullable Float changeRatio) {
    }

    /**
     * What the person may do with the document, so that the interface only offers that.
     *
     * @param newVersion   import a new version: a contributor, unless the document is archived
     * @param changeStatus between Draft, Published and Obsolete: a contributor; for an archived
     *                     document, a maintainer
     * @param archive      archive the document, or bring it back: a maintainer of its space
     */
    record DocumentActions(boolean edit, boolean delete, boolean manageAccess, boolean retry, boolean changeOwner,
            boolean newVersion, boolean changeStatus, boolean archive) {

        /** Nothing: for a document the person no longer sees once they handed it over. */
        static final DocumentActions NONE = new DocumentActions(false, false, false, false, false, false, false, false);
    }

    record SpaceLink(UUID id, String name, String slug, String color) {
    }

    record FolderLink(UUID id, String name) {
    }

    record PersonLink(UUID id, String displayName) {
    }

    record DocumentLink(UUID id, String title) {
    }

    private final JdbcClient jdbc;
    private final OrganizationMembers people;
    private final FileStore store;

    DocumentViews(JdbcClient jdbc, OrganizationMembers people, FileStore store) {
        this.jdbc = jdbc;
        this.people = people;
        this.store = store;
    }

    /**
     * @param replacedBy the document that replaces an obsolete one, once the caller checked that the
     *                   person may read it
     * @param newer      the latest version, when it is not the current one
     */
    DocumentView view(Space space, DocumentRepository.Row row, DocumentActions actions, @Nullable DocumentLink duplicateOf,
            @Nullable DocumentLink replacedBy, DocumentRepository.@Nullable Version newer) {
        List<UUID> authors = newer == null || newer.authorId() == null ? List.of() : List.of(newer.authorId());
        Map<UUID, OrganizationMember> names = names(space.organizationId(), List.of(row), authors);
        DocumentRepository.Version version = row.version();
        FolderLink folder = folder(row.folderId());
        return new DocumentView(row.id(), row.title(), row.description(), row.category(), row.status(),
                row.statusChangedAt(), replacedBy, row.restricted(),
                new SpaceLink(space.id(), space.name(), space.slug(), space.color()), folder,
                person(names, row.ownerId()), row.createdAt(), row.updatedAt(), row.versionCount(),
                version(space.organizationId(), version, names),
                newer == null ? null : new NewerVersion(newer.number(), newer.extractionStatus(), newer.extractionError(),
                        newer.createdAt(), person(names, newer.authorId())),
                actions, duplicateOf);
    }

    /** A version, in the history of a document. */
    VersionView version(UUID organizationId, DocumentRepository.Version version, Map<UUID, OrganizationMember> names) {
        return new VersionView(version.id(), version.number(), version.filename(), version.mediaType(),
                version.sizeBytes(), version.language(), version.pageCount(), version.sectionCount(),
                version.extractionStatus(), version.extractionError(), version.textTruncated(),
                version.extractedAt(), version.createdAt(), person(names, version.authorId()),
                version.sha256(), store.exists(organizationId, version.sha256()), version.note(), version.changeRatio());
    }

    /** The versions of a document, with the names of their authors. */
    List<VersionView> versions(UUID organizationId, List<DocumentRepository.Version> versions) {
        Set<UUID> ids = new HashSet<>();
        versions.forEach(version -> {
            if (version.authorId() != null) {
                ids.add(version.authorId());
            }
        });
        Map<UUID, OrganizationMember> names = ids.isEmpty() ? Map.of() : people.find(organizationId, ids);
        return versions.stream().map(version -> version(organizationId, version, names)).toList();
    }

    @Nullable FolderLink folder(@Nullable UUID folderId) {
        return folderId == null ? null : jdbc.sql("select id, name from folder where id = ?")
                .param(folderId)
                .query((rs, n) -> new FolderLink(rs.getObject("id", UUID.class), rs.getString("name")))
                .optional()
                .orElse(null);
    }

    /** @param newer the latest version of each document whose latest version is not the current one */
    List<DocumentItem> items(UUID organizationId, List<DocumentRepository.Row> rows,
            Map<UUID, DocumentRepository.Version> newer) {
        Map<UUID, OrganizationMember> names = names(organizationId, rows, List.of());
        return rows.stream().map(row -> item(row, names, newer.get(row.id()))).toList();
    }

    private static DocumentItem item(DocumentRepository.Row row, Map<UUID, OrganizationMember> names,
            DocumentRepository.@Nullable Version newer) {
        DocumentRepository.Version version = row.version();
        return new DocumentItem(row.id(), row.title(), row.folderId(), row.category(), row.status(), row.restricted(),
                person(names, row.ownerId()), row.updatedAt(), version.number(), version.filename(),
                version.mediaType(), version.sizeBytes(), version.extractionStatus(), version.extractionError(),
                version.language(), version.pageCount(), version.sectionCount(),
                newer == null ? null : new NewerVersion(newer.number(), newer.extractionStatus(), newer.extractionError(),
                        newer.createdAt(), null));
    }

    private Map<UUID, OrganizationMember> names(UUID organizationId, Collection<DocumentRepository.Row> rows,
            Collection<UUID> others) {
        Set<UUID> ids = new HashSet<>(others);
        for (DocumentRepository.Row row : rows) {
            if (row.ownerId() != null) {
                ids.add(row.ownerId());
            }
            if (row.version().authorId() != null) {
                ids.add(row.version().authorId());
            }
        }
        return ids.isEmpty() ? Map.of() : people.find(organizationId, ids);
    }

    private static @Nullable PersonLink person(Map<UUID, OrganizationMember> names, @Nullable UUID id) {
        if (id == null) {
            return null;
        }
        OrganizationMember member = names.get(id);
        // Someone who left the organization keeps no name here.
        return member == null ? null : new PersonLink(id, member.displayName());
    }
}
