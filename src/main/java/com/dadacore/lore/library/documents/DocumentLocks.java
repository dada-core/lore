package com.dadacore.lore.library.documents;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The rows a deletion takes, locked in the order the end of an extraction takes them: the job,
 * then the version, then the document. A document deleted while the text of one of its versions is
 * being recorded then waits for it, instead of each waiting for the other.
 */
@Component
public class DocumentLocks {

    private final JdbcClient jdbc;

    DocumentLocks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Before the deletion of a document, or of every document of a space. */
    public void forDeletion(@Nullable UUID documentId, @Nullable UUID spaceId) {
        jdbc.sql("""
                select j.version_id from extraction_job j
                join document_version v on v.id = j.version_id
                join document d on d.id = v.document_id
                where d.id = ? or d.space_id = ?
                order by j.version_id
                for update of j
                """)
                .params(documentId, spaceId)
                .query(UUID.class)
                .list();
        jdbc.sql("""
                select v.id from document_version v
                join document d on d.id = v.document_id
                where d.id = ? or d.space_id = ?
                order by v.id
                for update of v
                """)
                .params(documentId, spaceId)
                .query(UUID.class)
                .list();
    }
}
