package com.dadacore.lore.ingestion.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import com.dadacore.lore.ingestion.ExtractionWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/** The command line of an extraction process. */
class WorkerProcessTests {

    @TempDir
    Path work;

    @Test
    void theXmlParsersOfTheProcessReachNoExternalResource() throws Exception {
        List<String> command = WorkerProcess.command(DataSize.ofMegabytes(256), work, false);

        // Empty: no protocol at all, for every parser of the JDK, whatever the library that uses it.
        List<String> options = command.subList(0, command.indexOf(ExtractionWorker.class.getName()));
        assertThat(options).contains(
                "-Djavax.xml.accessExternalDTD=",
                "-Djavax.xml.accessExternalSchema=",
                "-Djavax.xml.accessExternalStylesheet=");
    }
}
