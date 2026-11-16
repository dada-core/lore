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
import com.dadacore.lore.shared.SqlCondition;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The tables {@code document}, {@code document_version} and {@code chunk}. */
@Repository
class DocumentRepository {

    /** A document, with its current version. */
    record Row(UUID id, UUID spaceId, @Nullable UUID folderId, String title, @Nullable String description,
            @Nullable String category, String status, boolean restricted, @Nullable UUID ownerId,
            @Nullable UUID createdBy, Instant createdAt, Instant updatedAt, int versionCount, Version version) {
    }

    /** A version of a document. */
    record Version(UUID id, int number, String sha256, String filename, String mediaType, long sizeBytes,
            @Nullable UUID authorId, Instant createdAt, String extractionStatus, @Nullable String extractionError,
            @Nullable Instant extractedAt, @Nullable String language, @Nullable Integer pageCount,
            @Nullable Integer sectionCount, boolean textTruncated) {
    }

    /** What a new document needs. */
    record NewDocument(UUID spaceId, @Nullable UUID folderId, String title, @Nullable String category,
            String status, boolean restricted, UUID createdBy) {
    }

    /** Counts of the documents a person can see in a space. */
    record Summary(long total, long inProgress, long scanned, long failed, List<FolderCount> folders) {
    }

    /** {@code folderId} null: at the root of the space. */
    record FolderCount(@Nullable UUID folderId, long count) {
    }

    private static final String COLUMNS = """
            select d.id, d.space_id, d.folder_id, d.title, d.description, d.category, d.status, d.restricted,
                d.owner_id, d.created_by, d.created_at, d.updated_at,
                (select count(*) from document_version c where c.document_id = d.id) as version_count,
                v.id as version_id, v.number, v.sha256, v.filename, v.media_type, v.size_bytes, v.author_id,
                v.created_at as version_created_at, v.extraction_status, v.extraction_error, v.extracted_at,
                v.language, v.page_count, v.section_count, v.text_truncated
            from document d
            join space s on s.id = d.space_id
            join document_version v on v.id = d.current_version_id
            """;

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
     * first), then most recently updated first.
     */
    List<Row> list(UUID spaceId, SqlCondition readable, Filter filter, long offset, int limit) {
        List<Object> params = new ArrayList<>();
        String where = where(spaceId, readable, filter, params);
        params.add(limit);
        params.add(offset);
        return jdbc.sql(COLUMNS + where + " order by d.folder_id nulls first, d.updated_at desc, d.id limit ? offset ?")
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

    /** Which documents a list shows. */
    record Filter(@Nullable UUID folderId, boolean rootOnly, @Nullable Boolean restricted, List<UUID> ids) {
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
        return where.toString();
    }

    Summary summary(UUID spaceId, SqlCondition readable) {
        List<Object> params = new ArrayList<>();
        params.add(spaceId);
        params.addAll(readable.params());
        String from = " from document d join space s on s.id = d.space_id join document_version v on v.id = d.current_version_id"
                + " where d.space_id = ? and (" + readable.sql() + ")";
        long[] totals = new long[4];
        jdbc.sql("select count(*) as total,"
                        + " count(*) filter (where v.extraction_status = 'PENDING') as in_progress,"
                        + " count(*) filter (where v.extraction_status = 'SCANNED') as scanned,"
                        + " count(*) filter (where v.extraction_status = 'FAILED') as failed" + from)
                .params(params)
                .query(rs -> {
                    totals[0] = rs.getLong("total");
                    totals[1] = rs.getLong("in_progress");
                    totals[2] = rs.getLong("scanned");
                    totals[3] = rs.getLong("failed");
                });
        List<FolderCount> folders = jdbc.sql("select d.folder_id, count(*) as documents" + from + " group by d.folder_id")
                .params(params)
                .query((rs, row) -> new FolderCount(rs.getObject("folder_id", UUID.class), rs.getLong("documents")))
                .list();
        return new Summary(totals[0], totals[1], totals[2], totals[3], folders);
    }

    /** The number of documents the condition allows in each space. */
    Map<UUID, Long> countBySpace(Collection<UUID> spaceIds, SqlCondition readable) {
        Map<UUID, Long> counts = new HashMap<>();
        if (spaceIds.isEmpty()) {
            return counts;
        }
        List<Object> params = new ArrayList<>(spaceIds);
        params.addAll(readable.params());
        jdbc.sql("select d.space_id, count(*) as documents from document d join space s on s.id = d.space_id"
                        + " where d.space_id in (" + String.join(", ", Collections.nCopies(spaceIds.size(), "?")) + ")"
                        + " and (" + readable.sql() + ") group by d.space_id")
                .params(params)
                .query(rs -> {
                    counts.put(rs.getObject("space_id", UUID.class), rs.getLong("documents"));
                });
        return counts;
    }

    /** The documents of the organization imported by the person, still extracting or failed. */
    List<Row> attention(UUID organizationId, UUID userId, SqlCondition readable, int limit) {
        List<Object> params = new ArrayList<>();
        params.add(organizationId);
        params.add(userId);
        params.addAll(readable.params());
        params.add(limit);
        return jdbc.sql(COLUMNS + " where s.organization_id = ? and d.created_by = ? and (" + readable.sql() + ")"
                        + " and v.extraction_status in ('PENDING', 'FAILED') order by v.created_at desc limit ?")
                .params(params)
                .query(DocumentRepository::row)
                .list();
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

    void update(UUID id, String title, @Nullable String description, @Nullable String category) {
        jdbc.sql("update document set title = ?, description = ?, category = ?, updated_at = now() where id = ?")
                .params(title, description, category, id)
                .update();
    }

    void move(UUID id, @Nullable UUID folderId) {
        jdbc.sql("update document set folder_id = ?, updated_at = now() where id = ?").params(folderId, id).update();
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
        return jdbc.sql("""
                select v.id as version_id, v.number, v.sha256, v.filename, v.media_type, v.size_bytes, v.author_id,
                    v.created_at as version_created_at, v.extraction_status, v.extraction_error, v.extracted_at,
                    v.language, v.page_count, v.section_count, v.text_truncated, v.document_id
                from document_version v where v.id = ?
                """)
                .param(versionId)
                .query((rs, row) -> version(rs))
                .optional();
    }

    void extracted(UUID versionId, String status, String language, @Nullable Integer pageCount, int sectionCount,
            boolean truncated) {
        jdbc.sql("""
                update document_version set extraction_status = ?, extraction_error = null, extracted_at = now(),
                    language = ?, page_count = ?, section_count = ?, text_truncated = ?
                where id = ?
                """)
                .params(status, language, pageCount, sectionCount, truncated, versionId)
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

    void replaceChunks(UUID versionId, List<ExtractedChunk> chunks) {
        jdbc.sql("delete from chunk where version_id = ?").param(versionId).update();
        template.batchUpdate("""
                insert into chunk (version_id, ordinal, anchor, page, headings, content) values (?, ?, ?, ?, ?, ?)
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
            }

            @Override
            public int getBatchSize() {
                return chunks.size();
            }
        });
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
                version(rs));
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
                rs.getBoolean("text_truncated"));
    }

    private static Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
