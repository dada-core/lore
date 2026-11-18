package com.dadacore.lore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import javax.sql.DataSource;

import com.dadacore.lore.support.ApiTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The migrations on the data of a published version: they are replayed in a schema of their own,
 * up to the version published, then the data are written, then the next migration runs.
 */
class MigrationTests extends ApiTest {

    private static final String SCHEMA = "migration_check";

    @Autowired
    DataSource dataSource;

    @AfterEach
    void dropSchema() {
        jdbc.sql("drop schema if exists " + SCHEMA + " cascade").update();
    }

    @Test
    void v6ExtractsAgainTheIndexedMarkdownWordAndTextDocumentsOfThePreview() {
        migrate("5");
        JdbcClient sql = JdbcClient.create(dataSource);
        UUID organization = sql.sql("insert into " + SCHEMA + ".organization (name, slug) values ('DadaGroup', 'dadagroup') returning id")
                .query(UUID.class).single();
        UUID space = sql.sql("insert into " + SCHEMA + ".space (organization_id, name, slug, color) values (?, 'Infrastructure',"
                + " 'infrastructure', '#13705E') returning id").param(organization).query(UUID.class).single();
        UUID markdown = version(sql, space, "text/markdown", "INDEXED", null);
        UUID word = version(sql, space, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "INDEXED", null);
        UUID text = version(sql, space, "text/plain", "INDEXED", null);
        UUID pdf = version(sql, space, "application/pdf", "INDEXED", null);
        UUID scanned = version(sql, space, "application/pdf", "SCANNED", null);
        UUID failed = version(sql, space, "text/markdown", "FAILED", "corrupt");
        UUID pending = version(sql, space, "text/plain", "PENDING", null);
        sql.sql("insert into " + SCHEMA + ".extraction_job (version_id, organization_id, sha256, media_type)"
                + " values (?, ?, repeat('b', 64), 'text/plain')").params(pending, organization).update();

        migrate("6");

        assertThat(status(sql, markdown)).isEqualTo("PENDING");
        assertThat(status(sql, word)).isEqualTo("PENDING");
        assertThat(status(sql, text)).isEqualTo("PENDING");
        assertThat(status(sql, pdf)).isEqualTo("INDEXED");
        assertThat(status(sql, scanned)).isEqualTo("SCANNED");
        assertThat(status(sql, failed)).isEqualTo("FAILED");
        assertThat(status(sql, pending)).isEqualTo("PENDING");
        assertThat(sql.sql("select version_id from " + SCHEMA + ".extraction_job").query(UUID.class).list())
                .containsExactlyInAnyOrder(markdown, word, text, pending);
        assertThat(sql.sql("select organization_id from " + SCHEMA + ".extraction_job where version_id = ?").param(word)
                .query(UUID.class).single()).isEqualTo(organization);
    }

    @Test
    void v7ComputesTheVectorsOfThePassagesAlreadyImportedInTheirLanguage() {
        migrate("6");
        JdbcClient sql = JdbcClient.create(dataSource);
        UUID organization = sql.sql("insert into " + SCHEMA + ".organization (name, slug) values ('DadaGroup', 'dadagroup') returning id")
                .query(UUID.class).single();
        UUID space = sql.sql("insert into " + SCHEMA + ".space (organization_id, name, slug, color) values (?, 'Infrastructure',"
                + " 'infrastructure', '#13705E') returning id").param(organization).query(UUID.class).single();
        UUID english = version(sql, space, "text/markdown", "INDEXED", null);
        sql.sql("update " + SCHEMA + ".document_version set language = 'en' where id = ?").param(english).update();
        sql.sql("insert into " + SCHEMA + ".chunk (version_id, ordinal, anchor, headings, content) values (?, 1, 'h-1',"
                + " '{Restarting}', 'The servers were restarted.')").param(english).update();
        UUID unknown = version(sql, space, "text/plain", "FAILED", "corrupt");

        migrate("7");

        assertThat(sql.sql("select language from " + SCHEMA + ".chunk where version_id = ?").param(english)
                .query(String.class).single()).isEqualTo("en");
        assertThat(sql.sql("select search_language from " + SCHEMA + ".document where current_version_id = ?").param(english)
                .query(String.class).single()).isEqualTo("en");
        assertThat(sql.sql("select search_language from " + SCHEMA + ".document where current_version_id = ?").param(unknown)
                .query(String.class).optional()).isEmpty();
        // The English stemmer read the passage, and its section title weighs more than its text.
        assertThat(sql.sql("select tsv_lang @@ to_tsquery('" + SCHEMA + ".lore_en', 'restart') from " + SCHEMA
                + ".chunk where version_id = ?").param(english).query(Boolean.class).single()).isTrue();
        assertThat(sql.sql("select tsv_lang::text from " + SCHEMA + ".chunk where version_id = ?").param(english)
                .query(String.class).single()).contains("'restart':1B");
        assertThat(sql.sql("select language_chosen from " + SCHEMA + ".document_version where id = ?").param(english)
                .query(Boolean.class).single()).isFalse();
    }

    @Test
    void v8DatesEachStatusFromTheCreationOfItsDocumentAndChangesNothingElse() {
        migrate("7");
        JdbcClient sql = JdbcClient.create(dataSource);
        UUID organization = sql.sql("insert into " + SCHEMA + ".organization (name, slug) values ('DadaGroup', 'dadagroup') returning id")
                .query(UUID.class).single();
        UUID space = sql.sql("insert into " + SCHEMA + ".space (organization_id, name, slug, color) values (?, 'Infrastructure',"
                + " 'infrastructure', '#13705E') returning id").param(organization).query(UUID.class).single();
        UUID indexed = version(sql, space, "text/markdown", "INDEXED", null);
        sql.sql("update " + SCHEMA + ".document set created_at = '2026-09-02T08:00:00Z', status = 'OBSOLETE'"
                + " where current_version_id = ?").param(indexed).update();
        UUID failed = version(sql, space, "text/plain", "FAILED", "corrupt");
        String before = sql.sql("select string_agg(id::text || title || status, ',' order by id) from " + SCHEMA + ".document")
                .query(String.class).single();

        migrate("8");

        assertThat(sql.sql("select status_changed_at = created_at from " + SCHEMA + ".document where current_version_id = ?")
                .param(indexed).query(Boolean.class).single()).isTrue();
        assertThat(sql.sql("select replaced_by_id from " + SCHEMA + ".document where current_version_id = ?").param(indexed)
                .query(UUID.class).optional()).isEmpty();
        // The first versions changed all of their document; a failure, nobody knows.
        assertThat(sql.sql("select change_ratio from " + SCHEMA + ".document_version where id = ?").param(indexed)
                .query(Float.class).single()).isEqualTo(1f);
        assertThat(sql.sql("select change_ratio from " + SCHEMA + ".document_version where id = ?").param(failed)
                .query(Float.class).optional()).isEmpty();
        assertThat(sql.sql("select string_agg(id::text || title || status, ',' order by id) from " + SCHEMA + ".document")
                .query(String.class).single()).isEqualTo(before);
        // A replacement belongs to obsolete documents only.
        UUID other = sql.sql("select id from " + SCHEMA + ".document where current_version_id = ?").param(failed)
                .query(UUID.class).single();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sql.sql("update " + SCHEMA + ".document set replaced_by_id = id"
                + " where id = ?").param(other).update()).hasMessageContaining("document_replaced_by_check");
    }

    private void migrate(String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private static UUID version(JdbcClient sql, UUID space, String mediaType, String status, String error) {
        UUID document = sql.sql("insert into " + SCHEMA + ".document (space_id, title, status) values (?, 'Document', 'DRAFT')"
                + " returning id").param(space).query(UUID.class).single();
        UUID version = sql.sql("insert into " + SCHEMA + ".document_version (document_id, number, sha256, filename, media_type,"
                        + " size_bytes, extraction_status, extraction_error) values (?, 1, repeat('a', 64), 'f', ?, 10, ?, ?) returning id")
                .params(document, mediaType, status, error)
                .query(UUID.class).single();
        sql.sql("update " + SCHEMA + ".document set current_version_id = ? where id = ?").params(version, document).update();
        return version;
    }

    private static String status(JdbcClient sql, UUID version) {
        return sql.sql("select extraction_status from " + SCHEMA + ".document_version where id = ?").param(version)
                .query(String.class).single();
    }
}
