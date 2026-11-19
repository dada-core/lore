package com.dadacore.lore;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** The lore command: its list of commands answers without a database, as on a fresh archive. */
class CommandLineTests {

    @Test
    void helpListsTheCommandsWithoutADatabase() {
        PrintStream out = System.out;
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        // A port where nothing listens: a connection would fail.
        System.setProperty("lore.db.host", "127.0.0.1");
        System.setProperty("lore.db.port", "1");
        System.setOut(new PrintStream(printed, true, StandardCharsets.UTF_8));
        try {
            assertThat(CommandLine.run(new String[] {"help"})).isZero();
        } finally {
            System.setOut(out);
            System.clearProperty("lore.db.host");
            System.clearProperty("lore.db.port");
        }
        assertThat(printed.toString(StandardCharsets.UTF_8)).contains("reset-password");
    }

    @Test
    void helpIsACommandUnderEachOfItsNamesAndAnOptionStartsTheServer() {
        for (String help : new String[] {"help", "--help", "-h"}) {
            assertThat(CommandLine.handles(new String[] {help})).as(help).isTrue();
        }
        assertThat(CommandLine.handles(new String[] {"reset-password", "wilfried.kouakou@dada.group"})).isTrue();
        assertThat(CommandLine.handles(new String[] {"--server.port=8081"})).isFalse();
        assertThat(CommandLine.handles(new String[] {})).isFalse();
    }
}
