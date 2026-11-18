package com.dadacore.lore.library.documents;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.dadacore.lore.ingestion.ExtractedChunk;
import com.dadacore.lore.ingestion.Rendering;
import com.dadacore.lore.shared.SqlCondition;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The tables {@code document}, {@code document_version}, {@code chunk} and {@code rendering}. */
@Repository
class DocumentRepository {

    /**
     * A document, with its current version.
     *
     * @param statusChangedAt since when it has its status
     * @param replacedById    for an obsolete document, the one that replaces it, whoever may read it
     * @param latestNumber    the number of its latest version, above the current one while a new
     *                        version is extracted or after it failed
     */
    record Row(UUID id, UUID spaceId, @Nullable UUID folderId, String title, @Nullable String description,
            @Nullable String category, String status, boolean restricted, @Nullable UUID ownerId,
            @Nullable UUID createdBy, Instant createdAt, Instant updatedAt, int versionCount, Version version,
            Instant statusChangedAt, @Nullable UUID replacedById, int latestNumber) {

        boolean archived() {
            return "ARCHIVED".equals(status);
        }
    }

    /**
     * A version of a document.
     *
     * @param note           what changes, written at its import; none for the first version
     * @param changeRatio    the share of passages changed from the version before, from 0 to 1; null
     *                       when unknown
     * @param languageChosen its language was chosen by a person, and is kept when it is extracted again
     */
    record Version(UUID id, int number, String sha256, String filename, String mediaType, long sizeBytes,
            @Nullable UUID authorId, Instant createdAt, String extractionStatus, @Nullable String extractionError,
            @Nullable Instant extractedAt, @Nullable String language, @Nullable Integer pageCount,
            @Nullable Integer sectionCount, boolean textTruncated, @Nullable String note, @Nullable Float changeRatio,
            boolean languageChosen) {
    }

    /** What a new document needs. */
    record NewDocument(UUID spaceId, @Nullable UUID folderId, String title, @Nullable String category,
            String status, boolean restricted, UUID createdBy) {
    }

    /**
     * Counts of the documents a person can see in a space; the archived ones apart, counted nowhere
     * else.
     */
    record Summary(long total, long inProgress, long scanned, long failed, long archived, List<FolderCount> folders) {
    }

    /** {@code folderId} null: at the root of the space. */
    record FolderCount(@Nullable UUID folderId, long count) {
    }

    /** The columns of a version, as {@link #version(ResultSet)} reads them. */
    private static final String VERSION_COLUMNS = """
            v.id as version_id, v.number, v.sha256, v.filename, v.media_type, v.size_bytes, v.author_id,
                v.created_at as version_created_at, v.extraction_status, v.extraction_error, v.extracted_at,
                v.language, v.page_count, v.section_count, v.text_truncated, v.note, v.change_ratio, v.language_chosen""";

    private static final String COLUMNS = """
            select d.id, d.space_id, d.folder_id, d.title, d.description, d.category, d.status, d.restricted,
                d.owner_id, d.created_by, d.created_at, d.updated_at, d.status_changed_at, d.replaced_by_id,
                (select count(*) from document_version c where c.document_id = d.id) as version_count,
                (select max(c.number) from document_version c where c.document_id = d.id) as latest_number,
                """ + VERSION_COLUMNS + """

            from document d
            join space s on s.id = d.space_id
            join document_version v on v.id = d.current_version_id
            """;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<Rendering.Place>> PLACES = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    DocumentRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    /** The document within the organization, before any check of permissions. */
    Optional<Row> find(UUID organizationId, UUID id) {
        return jdbc.sql(COLUMNS + " where s.organization_id = ? and d.id = ?")
                .params(organizationId, id)
                .query(DocumentRepository::row)
                .optional();
    }

    /**
     * A page of the documents of a space that the condition allows, by folder (those without one
     * first), then in the order asked.
     */
    List<Row> list(UUID spaceId, SqlCondition readable, Filter filter, Sort sort, boolean descending, long offset,
            int limit) {
        List<Object> params = new ArrayList<>();
        String where = where(spaceId, readable, filter, params);
        params.add(limit);
        params.add(offset);
        String order = " order by d.folder_id nulls first, " + sort.column + (descending ? " desc" : " asc")
                + " nulls last, d.id limit ? offset ?";
        return jdbc.sql(COLUMNS + where + order)
                .params(params)
                .query(DocumentRepository::row)
                .list();
    }

    long count(UUID spaceId, SqlCondition readable, Filter filter) {
        List<Object> params = new ArrayList<>();
        String where = where(spaceId, readable, filter, params);
        return jdbc.sql("select count(*) from document d join space s on s.id = d.space_id"
                        + " join document_version v on v.id = d.current_version_id" + where)
                .params(params)
                .query(Long.class)
                .single();
    }

    /** The order of a list, within each folder. */
    enum Sort {
        UPDATED("d.updated_at"), TITLE("lower(d.title)"), CATEGORY("d.category"), STATUS("d.status"),
        SIZE("v.size_bytes");

        private final String column;

        Sort(String column) {
            this.column = column;
        }
    }

    /**
     * Which documents a list shows.
     *
     * @param statuses the statuses shown; none: all but Archived, which a list shows only when asked
     */
    record Filter(@Nullable UUID folderId, boolean rootOnly, @Nullable Boolean restricted, List<UUID> ids,
            List<String> statuses) {
    }

    private static String where(UUID spaceId, SqlCondition readable, Filter filter, List<Object> params) {
        StringBuilder where = new StringBuilder(" where d.space_id = ? and (").append(readable.sql()).append(')');
        params.add(spaceId);
        params.addAll(readable.params());
        if (filter.rootOnly()) {
            where.append(" and d.folder_id is null");
        } else if (filter.folderId() != null) {
            where.append(" and d.folder_id = ?");
            params.add(filter.folderId());
        }
        if (filter.restricted() != null) {
            where.append(" and d.restricted = ?");
            params.add(filter.restricted());
        }
        if (!filter.ids().isEmpty()) {
            where.append(" and d.id in (").append(String.join(", ", Collections.nCopies(filter.ids().size(), "?"))).append(')');
            params.addAll(filter.ids());
        }
        if (filter.statuses().isEmpty()) {
            where.append(" and d.status <> 'ARCHIVED'");
        } else {
            where.append(" and d.status = any(?)");
            params.add(filter.statuses().toArray(String[]::new));
        }
        return where.toString();
    }

    Summary summary(UUID spaceId, SqlCondition readable) {
        List<Object> params = new ArrayList<>();
        params.add(spaceId);
        params.addAll(readable.params());
        String from = " from document d join space s on s.id = d.space_id join document_version v on v.id = d.current_version_id"
                + " where d.space_id = ? and (" + readable.sql() + ")";
        long[] totals = new long[5];
        jdbc.sql("select count(*) filter (where d.status <> 'ARCHIVED') as total,"
                        + " count(*) filter (where d.status <> 'ARCHIVED' and v.extraction_status = 'PENDING') as in_progress,"
                        + " count(*) filter (where d.status <> 'ARCHIVED' and v.extraction_status = 'SCANNED') as scanned,"
                        + " count(*) filter (where d.status <> 'ARCHIVED' and v.extraction_status = 'FAILED') as failed,"
                        + " count(*) filter (where d.status = 'ARCHIVED') as archived" + from)
                .params(params)
                .query(rs -> {
                    totals[0] = rs.getLong("total");
                    totals[1] = rs.getLong("in_progress");
                    totals[2] = rs.getLong("scanned");
                    totals[3] = rs.getLong("failed");
                    totals[4] = rs.getLong("archived");
                });
        List<FolderCount> folders = jdbc.sql("select d.folder_id, count(*) as documents" + from
                        + " and d.status <> 'ARCHIVED' group by d.folder_id")
                .params(params)
                .query((rs, row) -> new FolderCount(rs.getObject("folder_id", UUID.class), rs.getLong("documents")))
                .list();
        return new Summary(totals[0], totals[1], totals[2], totals[3], totals[4], folders);
    }

    /** Every document of a space. */
    long countAll(UUID spaceId) {
        return jdbc.sql("select count(*) from document where space_id = ?").param(spaceId).query(Long.class).single();
    }

    /** The number of documents the condition allows in each space, the archived ones left out. */
    Map<UUID, Long> countBySpace(Collection<UUID> spaceIds, SqlCondition readable) {
        Map<UUID, Long> counts = new HashMap<>();
        if (spaceIds.isEmpty()) {
            return counts;
        }
        List<Object> params = new ArrayList<>(spaceIds);
        params.addAll(readable.params());
        jdbc.sql("select d.space_id, count(*) as documents from document d join space s on s.id = d.space_id"
                        + " where d.space_id in (" + String.join(", ", Collections.nCopies(spaceIds.size(), "?")) + ")"
                        + " and (" + readable.sql() + ") and d.status <> 'ARCHIVED' group by d.space_id")
                .params(params)
                .query(rs -> {
                    counts.put(rs.getObject("space_id", UUID.class), rs.getLong("documents"));
                });
        return counts;
    }

    /** Those of these documents of the organization that the condition allows, in no order. */
    List<Row> findAll(UUID organizationId, Collection<UUID> ids, SqlCondition readable) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        params.add(organizationId);
        params.addAll(ids);
        params.addAll(readable.params());
        return jdbc.sql(COLUMNS + " where s.organization_id = ? and d.id in ("
                        + String.join(", ", Collections.nCopies(ids.size(), "?")) + ") and (" + readable.sql() + ")")
                .params(params)
                .query(DocumentRepository::row)
                .list();
    }

    /** A version that needs the attention of who imported it, and its document. */
    record Attention(Row document, Version version) {
    }

    /**
     * The versions the person imported, still extracting or failed, as long as no later version has
     * become current: a first import, or a new version.
     */
    List<Attention> attention(UUID organizationId, UUID userId, SqlCondition readable, int limit) {
        List<Object> params = new ArrayList<>();
        params.add(organizationId);
        params.add(userId);
        params.addAll(readable.params());
        params.add(limit);
        List<Attention> attention = new ArrayList<>();
        jdbc.sql("""
                select d.id as document_id, a.id as version_id from document d
                join space s on s.id = d.space_id
                join document_version c on c.id = d.current_version_id
                join document_version a on a.document_id = d.id and a.number >= c.number
                where s.organization_id = ? and a.author_id = ? and (%s) and d.status <> 'ARCHIVED'
                  and a.extraction_status in ('PENDING', 'FAILED')
                order by a.created_at desc limit ?
                """.formatted(readable.sql()))
                .params(params)
                .query((rs, row) -> List.of(rs.getObject("document_id", UUID.class), rs.getObject("version_id", UUID.class)))
                .list()
                .forEach(ids -> find(organizationId, ids.get(0)).ifPresent(document ->
                        version(ids.get(1)).ifPresent(version -> attention.add(new Attention(document, version)))));
        return attention;
    }

    /** A document of the space, other than {@code except}, whose current file has this content. */
    Optional<Row> sameContent(UUID spaceId, String sha256, UUID except, SqlCondition readable) {
        List<Object> params = new ArrayList<>();
        params.add(spaceId);
        params.add(sha256);
        params.add(except);
        params.addAll(readable.params());
        return jdbc.sql(COLUMNS + " where d.space_id = ? and v.sha256 = ? and d.id <> ? and (" + readable.sql() + ")"
                        + " order by d.created_at limit 1")
                .params(params)
                .query(DocumentRepository::row)
                .optional();
    }

    UUID insert(NewDocument document) {
        return jdbc.sql("""
                insert into document (space_id, folder_id, title, category, status, restricted, owner_id, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """)
                .params(document.spaceId(), document.folderId(), document.title(), document.category(),
                        document.status(), document.restricted(), document.createdBy(), document.createdBy())
                .query(UUID.class)
                .single();
    }

    /** The first version of a new document, current at once (decision 16 of the import). */
    UUID insertVersion(UUID documentId, int number, String sha256, String filename, String mediaType, long size,
            UUID authorId) {
        UUID versionId = jdbc.sql("""
                insert into document_version (document_id, number, sha256, filename, media_type, size_bytes, author_id)
                values (?, ?, ?, ?, ?, ?, ?)
                returning id
                """)
                .params(documentId, number, sha256, filename, mediaType, size, authorId)
                .query(UUID.class)
                .single();
        jdbc.sql("update document set current_version_id = ?, updated_at = now() where id = ?")
                .params(versionId, documentId)
                .update();
        return versionId;
    }

    /** What a new version of a document needs. */
    record NewVersion(UUID documentId, int number, String sha256, String filename, String mediaType, long size,
            UUID authorId, @Nullable String note, @Nullable String statusWhenCurrent, @Nullable String chosenLanguage) {
    }

    /**
     * A new version, which becomes current only once its text is extracted. A language chosen by a
     * person on the current version is taken again, still chosen.
     */
    UUID insertNewVersion(NewVersion version) {
        return jdbc.sql("""
                insert into document_version (document_id, number, sha256, filename, media_type, size_bytes, author_id,
                    note, status_when_current, language, language_chosen, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp())
                returning id
                """)
                .params(version.documentId(), version.number(), version.sha256(), version.filename(), version.mediaType(),
                        version.size(), version.authorId(), version.note(), version.statusWhenCurrent(),
                        version.chosenLanguage(), version.chosenLanguage() != null)
                .query(UUID.class)
                .single();
    }

    /** The number the next version of a document takes: the largest plus one. */
    int nextNumber(UUID documentId) {
        return jdbc.sql("select coalesce(max(number), 0) + 1 from document_version where document_id = ?")
                .param(documentId)
                .query(Integer.class)
                .single();
    }

    /** The latest version of a document, current or not. */
    Version latest(UUID documentId) {
        return jdbc.sql("select " + VERSION_COLUMNS + " from document_version v where v.document_id = ?"
                        + " order by v.number desc limit 1")
                .param(documentId)
                .query((rs, row) -> version(rs))
                .single();
    }

    /** The versions of a document, the latest first. */
    List<Version> versions(UUID documentId, long offset, int limit) {
        return jdbc.sql("select " + VERSION_COLUMNS + " from document_version v where v.document_id = ?"
                        + " order by v.number desc limit ? offset ?")
                .params(documentId, limit, offset)
                .query((rs, row) -> version(rs))
                .list();
    }

    /**
     * Locks these documents, in the order of their identifiers, before a change of status: one that
     * replaces the other cannot change or go meanwhile. Returns their status, for those that exist.
     */
    java.util.Map<UUID, String> lockStatuses(UUID organizationId, Collection<UUID> ids) {
        java.util.Map<UUID, String> statuses = new HashMap<>();
        jdbc.sql("""
                select d.id, d.status from document d
                where d.id = any(?) and d.space_id in (select s.id from space s where s.organization_id = ?)
                order by d.id for no key update of d
                """)
                .params(ids.toArray(UUID[]::new), organizationId)
                .query(rs -> {
                    statuses.put(rs.getObject("id", UUID.class), rs.getString("status"));
                });
        return statuses;
    }

    /** The status of a document, and the one that replaces it while obsolete. */
    void changeStatus(UUID id, String status, @Nullable UUID replacedById, boolean statusChanged) {
        jdbc.sql("""
                update document set status = ?, replaced_by_id = ?, updated_at = now(),
                    status_changed_at = case when ? then clock_timestamp() else status_changed_at end
                where id = ?
                """)
                .params(status, replacedById, statusChanged, id)
                .update();
    }

    void update(UUID id, String title, @Nullable String description, @Nullable String category) {
        jdbc.sql("update document set title = ?, description = ?, category = ?, updated_at = now() where id = ?")
                .params(title, description, category, id)
                .update();
    }

    void move(UUID id, @Nullable UUID folderId) {
        jdbc.sql("update document set folder_id = ?, updated_at = now() where id = ?").params(folderId, id).update();
    }

    void changeOwner(UUID id, UUID ownerId) {
        jdbc.sql("update document set owner_id = ?, updated_at = now() where id = ?").params(ownerId, id).update();
    }

    void restrict(UUID id, boolean restricted) {
        jdbc.sql("update document set restricted = ?, updated_at = now() where id = ?").params(restricted, id).update();
    }

    void delete(UUID id) {
        jdbc.sql("delete from document where id = ?").param(id).update();
    }

    /** The contents used by the versions of a document, or of all the documents of a space. */
    List<String> contents(@Nullable UUID documentId, @Nullable UUID spaceId) {
        return jdbc.sql("""
                select distinct v.sha256 from document_version v join document d on d.id = v.document_id
                where d.id = ? or d.space_id = ?
                """)
                .params(documentId, spaceId)
                .query(String.class)
                .list();
    }

    boolean folderOf(UUID spaceId, UUID folderId) {
        return jdbc.sql("select exists (select 1 from folder where id = ? and space_id = ?)")
                .params(folderId, spaceId)
                .query(Boolean.class)
                .single();
    }

    /** The versions of an organization that use this content. */
    boolean used(UUID organizationId, String sha256) {
        return jdbc.sql("""
                select exists (select 1 from document_version v join document d on d.id = v.document_id
                    join space s on s.id = d.space_id where s.organization_id = ? and v.sha256 = ?)
                """)
                .params(organizationId, sha256)
                .query(Boolean.class)
                .single();
    }

    /** Locks several contents, in their order, like {@link #lockContent}. */
    void lockContents(UUID organizationId, List<String> sha256s) {
        jdbc.sql("""
                select pg_advisory_xact_lock(hashtextextended(keys.content, 0))
                from (select cast(? as text) || ':' || c.sha256 as content
                      from unnest(cast(? as text[])) as c(sha256) order by 1) keys
                """)
                .params(organizationId.toString(), sha256s.toArray(String[]::new))
                .query(rs -> {
                });
    }

    /** Of these contents, those no version of the organization uses. */
    List<String> unused(UUID organizationId, List<String> sha256s) {
        return jdbc.sql("""
                select c.sha256 from unnest(cast(? as text[])) as c(sha256)
                where not exists (select 1 from document_version v join document d on d.id = v.document_id
                    join space s on s.id = d.space_id where s.organization_id = ? and v.sha256 = c.sha256)
                """)
                .params(sha256s.toArray(String[]::new), organizationId)
                .query(String.class)
                .list();
    }

    /** Serializes the storing and the deletion of one content of an organization. */
    void lockContent(UUID organizationId, String sha256) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(?, 0))")
                .param(organizationId + ":" + sha256)
                .query(rs -> {
                });
    }

    /** What the organization stores, for Administration › Storage and import. */
    record StorageUse(long usedBytes, long documents, long inProgress, long scanned, long failed) {
    }

    StorageUse use(UUID organizationId) {
        long used = jdbc.sql("""
                select coalesce(sum(size_bytes), 0) from (
                    select distinct on (v.sha256) v.size_bytes from document_version v
                    join document d on d.id = v.document_id join space s on s.id = d.space_id
                    where s.organization_id = ?) contents
                """)
                .param(organizationId)
                .query(Long.class)
                .single();
        return jdbc.sql("""
                select count(*) as documents,
                    count(*) filter (where v.extraction_status = 'PENDING') as in_progress,
                    count(*) filter (where v.extraction_status = 'SCANNED') as scanned,
                    count(*) filter (where v.extraction_status = 'FAILED') as failed
                from document d join space s on s.id = d.space_id join document_version v on v.id = d.current_version_id
                where s.organization_id = ?
                """)
                .param(organizationId)
                .query((rs, row) -> new StorageUse(used, rs.getLong("documents"), rs.getLong("in_progress"),
                        rs.getLong("scanned"), rs.getLong("failed")))
                .single();
    }

    // The outcome of an extraction.

    Optional<Version> version(UUID versionId) {
        return jdbc.sql("select " + VERSION_COLUMNS + " from document_version v where v.id = ?")
                .param(versionId)
                .query((rs, row) -> version(rs))
                .optional();
    }

    /** A version of a document by its number. */
    Optional<Version> version(UUID documentId, int number) {
        return jdbc.sql("select " + VERSION_COLUMNS + " from document_version v where v.document_id = ? and v.number = ?")
                .params(documentId, number)
                .query((rs, row) -> version(rs))
                .optional();
    }

    /**
     * A version whose extraction ended, its document, and what it asked for: its current version
     * locked, so that two versions that end at once become current one after the other.
     *
     * @param statusChangedSince the status of the document changed after the version was imported
     */
    record Ended(UUID documentId, UUID currentVersionId, int currentNumber, String status, @Nullable String statusWhenCurrent,
            boolean statusChangedSince, UUID organizationId, UUID spaceId, String spaceName, String title) {
    }

    Optional<Ended> ended(UUID versionId) {
        // Locked alone, then read: joined to its current version, a document changed meanwhile by
        // another version that became current would be checked again with the former one, and lost.
        Optional<UUID> documentId = jdbc.sql("""
                select d.id from document d
                where d.id = (select v.document_id from document_version v where v.id = ?)
                for no key update
                """)
                .param(versionId)
                .query(UUID.class)
                .optional();
        if (documentId.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                select d.id, d.current_version_id, c.number, d.status, v.status_when_current,
                    d.status_changed_at > v.created_at as changed_since, s.organization_id, s.id as space_id,
                    s.name as space_name, d.title
                from document d
                join space s on s.id = d.space_id
                join document_version c on c.id = d.current_version_id
                join document_version v on v.id = ? and v.document_id = d.id
                where d.id = ?
                """)
                .params(versionId, documentId.get())
                .query((rs, row) -> new Ended(rs.getObject("id", UUID.class), rs.getObject("current_version_id", UUID.class),
                        rs.getInt("number"), rs.getString("status"), rs.getString("status_when_current"),
                        rs.getBoolean("changed_since"), rs.getObject("organization_id", UUID.class),
                        rs.getObject("space_id", UUID.class), rs.getString("space_name"), rs.getString("title")))
                .optional();
    }

    /**
     * The share of passages changed from one version to the next, by the fingerprints of their texts:
     * those added and removed over those of both. Null when either has no passage (a scan).
     */
    @Nullable
    Float changeRatio(UUID before, UUID after) {
        return jdbc.sql("""
                with a as (select md5(content) as h, count(*) as n from chunk where version_id = ? group by 1),
                     b as (select md5(content) as h, count(*) as n from chunk where version_id = ? group by 1)
                select case when (select count(*) from a) = 0 or (select count(*) from b) = 0 then null
                    else (sum(abs(coalesce(a.n, 0) - coalesce(b.n, 0))) / sum(coalesce(a.n, 0) + coalesce(b.n, 0)))::real end
                from a full join b using (h)
                """)
                .params(before, after)
                .query((rs, row) -> (Float) rs.getObject(1, Float.class))
                .optional()
                .orElse(null);
    }

    /** The version becomes the current one of its document, which is indexed in its language. */
    void makeCurrent(UUID documentId, UUID versionId) {
        jdbc.sql("""
                update document d set current_version_id = v.id, search_language = v.language, updated_at = now()
                from document_version v where v.id = ? and d.id = ?
                """)
                .params(versionId, documentId)
                .update();
    }

    void changeRatio(UUID versionId, @Nullable Float ratio) {
        jdbc.sql("update document_version set change_ratio = ? where id = ?").params(ratio, versionId).update();
    }

    /**
     * The passages of a version no longer current: nothing reads them, the search looks in the
     * current version only. Its rendering and its file stay.
     */
    void deleteChunks(UUID versionId) {
        jdbc.sql("delete from chunk where version_id = ?").param(versionId).update();
    }

    /**
     * The outcome of an extraction. A language chosen by a person is kept when the same version is
     * extracted again: the language the version ends with is returned.
     */
    @Nullable
    String extracted(UUID versionId, String status, @Nullable String language, @Nullable Integer pageCount,
            int sectionCount, boolean truncated) {
        return jdbc.sql("""
                update document_version set extraction_status = ?, extraction_error = null, extracted_at = now(),
                    language = case when language_chosen then language else ? end,
                    page_count = ?, section_count = ?, text_truncated = ?
                where id = ?
                returning language
                """)
                .params(status, language, pageCount, sectionCount, truncated, versionId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /**
     * The language a person chose for a version: its passages are indexed again in that language,
     * and so is the title of its document, in the same transaction.
     */
    void changeLanguage(UUID documentId, UUID versionId, String language) {
        jdbc.sql("update document_version set language = ?, language_chosen = true where id = ?")
                .params(language, versionId)
                .update();
        jdbc.sql("update chunk set language = ? where version_id = ? and language <> ?")
                .params(language, versionId, language)
                .update();
        jdbc.sql("update document set search_language = ?, updated_at = now() where id = ?")
                .params(language, documentId)
                .update();
    }

    /** The title and the description of a document are indexed in the language of its current version. */
    void searchLanguage(UUID versionId) {
        jdbc.sql("""
                update document d set search_language = v.language
                from document_version v
                where v.id = ? and d.current_version_id = v.id and d.search_language is distinct from v.language
                """)
                .param(versionId)
                .update();
    }

    void failed(UUID versionId, String code) {
        jdbc.sql("""
                update document_version set extraction_status = 'FAILED', extraction_error = ?, extracted_at = now()
                where id = ?
                """)
                .params(code, versionId)
                .update();
    }

    /** Back to "in progress", for a new attempt asked for by a person. */
    void pending(UUID versionId) {
        jdbc.sql("""
                update document_version set extraction_status = 'PENDING', extraction_error = null, extracted_at = null
                where id = ?
                """)
                .param(versionId)
                .update();
    }

    /** The passages of a version, with its language, which their search vectors are computed in. */
    void replaceChunks(UUID versionId, List<ExtractedChunk> chunks, @Nullable String language) {
        jdbc.sql("delete from chunk where version_id = ?").param(versionId).update();
        String indexed = language == null ? "other" : language;
        template.batchUpdate("""
                insert into chunk (version_id, ordinal, anchor, page, headings, content, language) values (?, ?, ?, ?, ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int i) throws SQLException {
                ExtractedChunk chunk = chunks.get(i);
                statement.setObject(1, versionId);
                statement.setInt(2, chunk.ordinal());
                statement.setString(3, chunk.anchor());
                statement.setObject(4, chunk.page());
                Array headings = statement.getConnection().createArrayOf("text", chunk.headings().toArray());
                statement.setArray(5, headings);
                statement.setString(6, chunk.content());
                statement.setString(7, indexed);
            }

            @Override
            public int getBatchSize() {
                return chunks.size();
            }
        });
    }

    /** The rendering of a version, without its parts. */
    record RenderingRow(int renderer, int partCount, boolean truncated, List<Rendering.Place> places, Instant createdAt) {
    }

    /** Replaces the rendering of a version; none for a PDF, a failure or an older rendering left out. */
    void replaceRendering(UUID versionId, @Nullable Rendering rendering) {
        jdbc.sql("delete from rendering where version_id = ?").param(versionId).update();
        if (rendering == null || rendering.parts().isEmpty()) {
            return;
        }
        jdbc.sql("""
                insert into rendering (version_id, renderer, part_count, truncated, places)
                values (?, ?, ?, ?, cast(? as jsonb))
                """)
                .params(versionId, Rendering.RULES, rendering.parts().size(), rendering.truncated(),
                        JSON.writeValueAsString(rendering.places()))
                .update();
        List<String> parts = rendering.parts();
        template.batchUpdate("insert into rendering_part (version_id, number, html) values (?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement statement, int i) throws SQLException {
                        statement.setObject(1, versionId);
                        statement.setInt(2, i + 1);
                        statement.setString(3, parts.get(i));
                    }

                    @Override
                    public int getBatchSize() {
                        return parts.size();
                    }
                });
    }

    Optional<RenderingRow> rendering(UUID versionId) {
        return jdbc.sql("select renderer, part_count, truncated, places::text as places, created_at from rendering where version_id = ?")
                .param(versionId)
                .query((rs, row) -> new RenderingRow(rs.getInt("renderer"), rs.getInt("part_count"), rs.getBoolean("truncated"),
                        JSON.readValue(rs.getString("places"), PLACES), instant(rs.getTimestamp("created_at"))))
                .optional();
    }

    Optional<String> renderingPart(UUID versionId, int number) {
        return jdbc.sql("select html from rendering_part where version_id = ? and number = ?")
                .params(versionId, number)
                .query(String.class)
                .optional();
    }

    /** The title a Markdown document takes from its first title, unless someone changed it already. */
    void titleFromContent(UUID versionId, String generated, String title) {
        jdbc.sql("""
                update document d set title = ? from document_version v
                where v.id = ? and d.id = v.document_id and d.current_version_id = v.id and d.title = ?
                """)
                .params(title, versionId, generated)
                .update();
    }

    private static Row row(ResultSet rs, int row) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getObject("space_id", UUID.class),
                rs.getObject("folder_id", UUID.class),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("category"),
                rs.getString("status"),
                rs.getBoolean("restricted"),
                rs.getObject("owner_id", UUID.class),
                rs.getObject("created_by", UUID.class),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                rs.getInt("version_count"),
                version(rs),
                instant(rs.getTimestamp("status_changed_at")),
                rs.getObject("replaced_by_id", UUID.class),
                rs.getInt("latest_number"));
    }

    private static Version version(ResultSet rs) throws SQLException {
        return new Version(
                rs.getObject("version_id", UUID.class),
                rs.getInt("number"),
                rs.getString("sha256"),
                rs.getString("filename"),
                rs.getString("media_type"),
                rs.getLong("size_bytes"),
                rs.getObject("author_id", UUID.class),
                instant(rs.getTimestamp("version_created_at")),
                rs.getString("extraction_status"),
                rs.getString("extraction_error"),
                instant(rs.getTimestamp("extracted_at")),
                rs.getString("language"),
                (Integer) rs.getObject("page_count"),
                (Integer) rs.getObject("section_count"),
                rs.getBoolean("text_truncated"),
                rs.getString("note"),
                rs.getObject("change_ratio", Float.class),
                rs.getBoolean("language_chosen"));
    }

    private static Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
