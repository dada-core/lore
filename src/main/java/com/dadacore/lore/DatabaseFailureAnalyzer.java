package com.dadacore.lore;

import java.sql.SQLException;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.env.Environment;

/**
 * When PostgreSQL cannot be reached or refuses the connection, Lore stops with a message that
 * names the variable to check, instead of a stack trace. Docker then restarts it.
 */
public final class DatabaseFailureAnalyzer extends AbstractFailureAnalyzer<SQLException> {

    private final Environment environment;

    public DatabaseFailureAnalyzer(Environment environment) {
        this.environment = environment;
    }

    @Override
    protected @Nullable FailureAnalysis analyze(Throwable rootFailure, SQLException cause) {
        String state = cause.getSQLState();
        if (state == null) {
            return null;
        }
        String host = setting("lore.db.host", "postgres");
        String port = setting("lore.db.port", "5432");
        String name = setting("lore.db.name", "lore");
        String user = setting("lore.db.user", "lore");
        String database = "the PostgreSQL database '" + name + "' at " + host + ":" + port;
        if (state.equals("28P01") || state.equals("28000")) {
            return new FailureAnalysis(
                    "Lore could not sign in to " + database + " as '" + user + "': the password was refused.",
                    "Check LORE_DB_PASSWORD: it must be the password of the PostgreSQL user, "
                            + "POSTGRES_PASSWORD in the postgres service of docker-compose.yml.",
                    cause);
        }
        if (state.equals("3D000")) {
            return new FailureAnalysis(
                    "Lore reached PostgreSQL at " + host + ":" + port + ", but the database '" + name
                            + "' does not exist.",
                    "Check LORE_DB_NAME: it must match POSTGRES_DB in the postgres service.",
                    cause);
        }
        if (state.startsWith("08")) {
            return new FailureAnalysis(
                    "Lore could not reach " + database + ".",
                    "Check that the postgres service is running (docker compose ps), then LORE_DB_HOST "
                            + "and LORE_DB_PORT.",
                    cause);
        }
        return null;
    }

    private String setting(String name, String fallback) {
        String value = environment.getProperty(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
