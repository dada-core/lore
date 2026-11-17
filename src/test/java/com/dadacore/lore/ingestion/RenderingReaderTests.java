package com.dadacore.lore.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** The result of an extraction process, read back by Lore: a rendering stays within its bounds. */
class RenderingReaderTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void aRenderingIsReadBackAsItWasWritten() {
        Extraction written = new Extraction(false, "fr", null, false, null, List.of(), new Rendering(
                List.of("<section id=\"h-0\"><p>a</p></section>", "<section id=\"h-1\"><h2>b</h2></section>"),
                List.of(new Rendering.Place("h-0", 1, 0, null), new Rendering.Place("h-1", 2, 1, "b")), true));

        Extraction read = JSON.readValue(JSON.writeValueAsString(written), Extraction.class);

        assertThat(read.rendering()).isEqualTo(written.rendering());
        assertThat(JSON.readValue(JSON.writeValueAsString(written.withRendering(null)), Extraction.class).rendering()).isNull();
    }

    @Test
    void tooManyPartsOrPlacesAreRefusedWhileTheyAreRead() {
        String parts = "\"\",".repeat(Rendering.MAX_PARTS) + "\"\"";
        assertThatThrownBy(() -> JSON.readValue(result("{\"parts\":[" + parts + "],\"places\":[],\"truncated\":false}"),
                Extraction.class)).isInstanceOf(JacksonException.class);

        String place = "{\"anchor\":\"h-1\",\"part\":1,\"level\":1,\"title\":\"t\"},";
        String places = place.repeat(Rendering.MAX_PLACES) + place.substring(0, place.length() - 1);
        assertThatThrownBy(() -> JSON.readValue(result("{\"parts\":[\"x\"],\"places\":[" + places + "],\"truncated\":false}"),
                Extraction.class)).isInstanceOf(JacksonException.class);

        assertThatThrownBy(() -> new Rendering(java.util.Collections.nCopies(Rendering.MAX_PARTS + 1, ""), List.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
        // Holes in the lists: refused as unreadable, never a failure of the application.
        assertThatThrownBy(() -> JSON.readValue(result("{\"parts\":[null],\"places\":[],\"truncated\":false}"), Extraction.class))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> JSON.readValue(result("{\"parts\":[\"x\"],\"places\":[null],\"truncated\":false}"), Extraction.class))
                .isInstanceOf(JacksonException.class);
    }

    private static String result(String rendering) {
        return "{\"scanned\":false,\"language\":\"fr\",\"pageCount\":null,\"truncated\":false,\"title\":null,\"chunks\":[],"
                + "\"rendering\":" + rendering + "}";
    }
}
