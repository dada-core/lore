package com.dadacore.lore;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.mock.env.MockEnvironment;

/** A1: a database that cannot be reached names the variable to check. */
class DatabaseFailureAnalyzerTests {

    private final DatabaseFailureAnalyzer analyzer = new DatabaseFailureAnalyzer(
            new MockEnvironment().withProperty("lore.db.host", "db.internal"));

    private FailureAnalysis analyze(String sqlState) {
        return analyzer.analyze(new BeanCreationException("flyway",
                new IllegalStateException("pool", new SQLException("refused", sqlState))));
    }

    @Test
    void aRefusedPasswordNamesTheVariableOfThePassword() {
        FailureAnalysis analysis = analyze("28P01");
        assertThat(analysis.getDescription()).contains("db.internal:5432", "password was refused");
        assertThat(analysis.getAction()).contains("LORE_DB_PASSWORD", "POSTGRES_PASSWORD");
    }

    @Test
    void anUnreachableServerNamesTheHostAndPort() {
        FailureAnalysis analysis = analyze("08001");
        assertThat(analysis.getDescription()).contains("could not reach", "db.internal:5432");
        assertThat(analysis.getAction()).contains("LORE_DB_HOST", "LORE_DB_PORT");
    }

    @Test
    void aMissingDatabaseNamesTheVariableOfTheName() {
        assertThat(analyze("3D000").getAction()).contains("LORE_DB_NAME");
    }

    @Test
    void otherErrorsAreLeftToSpringBoot() {
        assertThat(analyze("42P01")).isNull();
    }
}
