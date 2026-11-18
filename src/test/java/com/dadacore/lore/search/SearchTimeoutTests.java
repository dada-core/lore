package com.dadacore.lore.search;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;

/** A search that takes too long is stopped by PostgreSQL, and says so. */
@TestPropertySource(properties = "lore.search.statement-timeout=1ms")
class SearchTimeoutTests extends ApiTest {

    @Test
    void aSearchThatTakesTooLongAnswers503() {
        Browser owner = setUpOrganization();
        String space = owner.post("/api/v1/spaces", body("name", "Infrastructure")).text("id");
        String id = owner.upload("/api/v1/spaces/" + space + "/documents", "long.txt", "Contenu".getBytes()).text("id");
        UUID version = jdbc.sql("select current_version_id from document where id = ?::uuid").param(id).query(UUID.class).single();
        // Twenty thousand passages that all hold the word searched: more than a millisecond of work.
        jdbc.sql("""
                insert into chunk (version_id, ordinal, anchor, headings, content, language)
                select ?, n, 'part-' || n, '{}', repeat('sauvegarde de la base de production ', 40), 'fr'
                from generate_series(1, 20000) n
                """).param(version).update();
        jdbc.sql("update document_version set extraction_status = 'INDEXED', extracted_at = now(), language = 'fr' where id = ?")
                .param(version).update();

        Response search = owner.get("/api/v1/search?q=sauvegarde");

        assertThat(search.status()).isEqualTo(503);
        assertThat(search.code()).isEqualTo("search_timeout");
        // The connections go back to the pool without the limits of the search.
        assertThat(owner.get("/api/v1/documents/" + id).status()).isEqualTo(200);
        for (int i = 0; i < 20; i++) {
            assertThat(jdbc.sql("select current_setting('statement_timeout') || ' ' || current_setting('enable_seqscan')")
                    .query(String.class).single()).isEqualTo("0 on");
        }
    }
}
