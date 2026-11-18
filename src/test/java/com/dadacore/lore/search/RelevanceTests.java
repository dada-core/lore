package com.dadacore.lore.search;

import static com.dadacore.lore.support.Browser.body;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.dadacore.lore.ingestion.ExtractedChunk;
import com.dadacore.lore.ingestion.Extraction;
import com.dadacore.lore.ingestion.ExtractionResults;
import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Browser;
import com.dadacore.lore.support.Response;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The thirty searches of the scenario, each with the document that must come first, among the
 * documents of the scenario and neighbours that share their words (search/relevance.json).
 */
class RelevanceTests extends ApiTest {

    @Autowired
    ExtractionResults results;

    @Test
    void eachSearchOfTheScenarioFindsItsDocumentFirst() throws Exception {
        JsonNode data;
        try (InputStream in = getClass().getResourceAsStream("/search/relevance.json")) {
            data = JsonMapper.builder().build().readTree(in);
        }
        Browser owner = setUpOrganization();
        String space = "/api/v1/spaces/" + owner.post("/api/v1/spaces", body("name", "Infrastructure")).text("id");
        Map<String, String> keys = new HashMap<>();
        int counter = 0;
        for (JsonNode target : data.path("targets")) {
            List<ExtractedChunk> passages = new ArrayList<>();
            for (JsonNode passage : target.path("passages")) {
                List<String> headings = new ArrayList<>();
                passage.path("h").forEach(heading -> headings.add(heading.asString()));
                passages.add(new ExtractedChunk(++counter, "h-" + counter, null, headings, passage.path("t").asString()));
            }
            String id = document(owner, space, ++counter, target.path("title").asString(), target.path("lang").asString(),
                    target.path("scanned").asBoolean(), passages);
            owner.patch("/api/v1/documents/" + id, body("description", target.path("description").asString()));
            jdbc.sql("update document set status = ? where id = ?::uuid").params(target.path("status").asString(), id).update();
            keys.put(id, target.path("key").asString());
        }
        for (JsonNode neighbour : data.path("neighbours")) {
            document(owner, space, ++counter, neighbour.path("title").asString(), neighbour.path("lang").asString(), false,
                    List.of(new ExtractedChunk(++counter, "h-1", null, List.of(), neighbour.path("text").asString())));
        }

        List<String> wrong = new ArrayList<>();
        for (JsonNode pair : data.path("relevance")) {
            String query = pair.get(0).asString();
            String expected = pair.get(1).asString();
            Response found = owner.get("/api/v1/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
            assertThat(found.status()).as(query + ": " + found).isEqualTo(200);
            JsonNode first = found.json().at("/items/0/document/id");
            String key = first.isMissingNode() ? "nothing" : keys.getOrDefault(first.asString(), "a neighbour");
            if (!expected.equals(key)) {
                wrong.add(query + " gives " + key + " instead of " + expected);
            }
        }
        assertThat(wrong).isEmpty();
    }

    private String document(Browser owner, String space, int n, String title, String language, boolean scanned,
            List<ExtractedChunk> passages) {
        Response created = owner.upload(space + "/documents", "document-" + n + ".txt", ("Contenu " + n).getBytes());
        String id = created.text("id");
        owner.patch("/api/v1/documents/" + id, body("title", title));
        UUID version = jdbc.sql("select current_version_id from document where id = ?::uuid").param(id).query(UUID.class).single();
        results.extracted(version, new Extraction(scanned, scanned ? null : language, null, false, null,
                scanned ? List.of() : passages, null));
        return id;
    }
}
