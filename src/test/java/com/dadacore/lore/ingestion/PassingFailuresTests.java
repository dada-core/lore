package com.dadacore.lore.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** The interface offers "Retry" for the same failures as the server. */
class PassingFailuresTests {

    @Test
    void theInterfaceKnowsTheSameFailuresThatMayPass() throws Exception {
        String source = Files.readString(Path.of("web/src/documents/documents.tsx"));
        Matcher declared = Pattern.compile("PASSING_FAILURES = new Set\\(\\[([^\\]]*)\\]\\)").matcher(source);
        assertThat(declared.find()).as("PASSING_FAILURES in documents.tsx").isTrue();

        Set<String> web = Pattern.compile("'([a-z_]+)'").matcher(declared.group(1)).results()
                .map(found -> found.group(1)).collect(Collectors.toSet());

        assertThat(web).isEqualTo(ExtractionResults.PASSING_FAILURES);
    }
}
