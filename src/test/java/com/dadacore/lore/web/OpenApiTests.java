package com.dadacore.lore.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.dadacore.lore.support.ApiTest;
import com.dadacore.lore.support.Response;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The API description, from which the interface's TypeScript types are generated.
 *
 * <p>{@code LORE_OPENAPI_OUTPUT=web/openapi.json ./mvnw test -Dtest=OpenApiTests} writes it to
 * a file ({@code npm run api} in {@code web/} does it, then generates the types).
 */
class OpenApiTests extends ApiTest {

    @Test
    void theApiIsDescribed() throws IOException {
        Response response = browser().get("/api/v1/openapi.json");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("paths").has("/api/v1/auth/login")).isTrue();
        assertThat(response.json().get("paths").has("/api/v1/admin/members/{id}")).isTrue();
        assertThat(response.json().get("paths").has("/api/v1/spaces/{spaceId}/members/{userId}")).isTrue();
        assertThat(response.json().get("paths").has("/api/v1/spaces/{spaceId}/folders/{folderId}")).isTrue();
        assertThat(response.json().get("paths").has("/api/v1/spaces/{spaceId}/documents")).isTrue();
        assertThat(response.json().get("paths").has("/api/v1/documents/{id}/access")).isTrue();
        assertThat(response.json().get("paths").has("/api/v1/admin/storage")).isTrue();
        // The import is a form with a file, described as such.
        assertThat(response.json().at("/paths/~1api~1v1~1spaces~1{spaceId}~1documents/post/requestBody/content")
                .has("multipart/form-data")).isTrue();
        // The Viewer parameter is filled by the server.
        assertThat(response.json().toString()).doesNotContain("\"name\":\"viewer\"");

        String output = System.getenv("LORE_OPENAPI_OUTPUT");
        if (output != null && !output.isBlank()) {
            JsonMapper mapper = JsonMapper.builder()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .build();
            Object tree = mapper.treeToValue(response.json(), Object.class);
            // Same bytes on every system: the continuous integration compares the file with the committed one.
            String json = mapper.writeValueAsString(tree).replace("\r\n", "\n");
            Files.writeString(Path.of(output), json + "\n", StandardCharsets.UTF_8);
        }
    }
}
