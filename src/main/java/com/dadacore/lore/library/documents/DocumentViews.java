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
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The documents as the API shows them, with the names of the people and of the folder. */
@Component
class DocumentViews {

    /** A document, in full: the page of a document. */
    record DocumentView(UUID id, String title, @Nullable String description, @Nullable String category, String status,
            boolean restricted, SpaceLink space, @Nullable FolderLink folder, @Nullable PersonLink owner,
            Instant createdAt, Instant updatedAt, VersionView currentVersion, DocumentActions actions,
            @Nullable DocumentLink duplicateOf) {
    }

    /** A document in a list. */
    record DocumentItem(UUID id, String title, @Nullable UUID folderId, @Nullable String category, String status,
            boolean restricted, @Nullable PersonLink owner, Instant updatedAt, int version, String filename,
            String mediaType, long sizeBytes, String extractionStatus, @Nullable String extractionError,
            @Nullable String language, @Nullable Integer pageCount, @Nullable Integer sectionCount) {
    }

    /**
     * @param extractionStatus {@code PENDING}, {@code INDEXED}, {@code SCANNED} or {@code FAILED}
     * @param extractionError  for a failure: {@code encrypted}, {@code corrupt}, {@code unsupported_content},
     *                         {@code timeout}, {@code extractor_crashed} or {@code file_missing}
     * @param textTruncated    the text went beyond what is kept, and was cut
     */
    record VersionView(UUID id, int number, String filename, String mediaType, long sizeBytes,
            @Nullable String language, @Nullable Integer pageCount, @Nullable Integer sectionCount,
            String extractionStatus, @Nullable String extractionError, boolean textTruncated,
            @Nullable Instant extractedAt, Instant createdAt, @Nullable PersonLink author) {
    }

    /** What the person may do with the document, so that the interface only offers that. */
    record DocumentActions(boolean edit, boolean delete, boolean manageAccess, boolean retry) {
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

    DocumentViews(JdbcClient jdbc, OrganizationMembers people) {
        this.jdbc = jdbc;
        this.people = people;
    }

    DocumentView view(Space space, DocumentRepository.Row row, DocumentActions actions, @Nullable DocumentLink duplicateOf) {
        Map<UUID, OrganizationMember> names = names(space.organizationId(), List.of(row));
        DocumentRepository.Version version = row.version();
        FolderLink folder = row.folderId() == null ? null : jdbc.sql("select id, name from folder where id = ?")
                .param(row.folderId())
                .query((rs, n) -> new FolderLink(rs.getObject("id", UUID.class), rs.getString("name")))
                .optional()
                .orElse(null);
        return new DocumentView(row.id(), row.title(), row.description(), row.category(), row.status(),
                row.restricted(), new SpaceLink(space.id(), space.name(), space.slug(), space.color()), folder,
                person(names, row.ownerId()), row.createdAt(), row.updatedAt(),
                new VersionView(version.id(), version.number(), version.filename(), version.mediaType(),
                        version.sizeBytes(), version.language(), version.pageCount(), version.sectionCount(),
                        version.extractionStatus(), version.extractionError(), version.textTruncated(),
                        version.extractedAt(), version.createdAt(), person(names, version.authorId())),
                actions, duplicateOf);
    }

    List<DocumentItem> items(UUID organizationId, List<DocumentRepository.Row> rows) {
        Map<UUID, OrganizationMember> names = names(organizationId, rows);
        return rows.stream().map(row -> item(row, names)).toList();
    }

    private static DocumentItem item(DocumentRepository.Row row, Map<UUID, OrganizationMember> names) {
        DocumentRepository.Version version = row.version();
        return new DocumentItem(row.id(), row.title(), row.folderId(), row.category(), row.status(), row.restricted(),
                person(names, row.ownerId()), row.updatedAt(), version.number(), version.filename(),
                version.mediaType(), version.sizeBytes(), version.extractionStatus(), version.extractionError(),
                version.language(), version.pageCount(), version.sectionCount());
    }

    private Map<UUID, OrganizationMember> names(UUID organizationId, Collection<DocumentRepository.Row> rows) {
        Set<UUID> ids = new HashSet<>();
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
