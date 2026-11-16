package com.dadacore.lore.library.documents;

import java.util.Optional;
import java.util.UUID;

import com.dadacore.lore.LoreProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

/**
 * The maximum size of an imported file: chosen by the administrators of the organization,
 * {@value #DEFAULT_MEGABYTES} MB by default, always within the ceiling {@code LORE_MAX_UPLOAD_SIZE}
 * set by whoever runs the server.
 */
@Service
class ImportSettings {

    static final int DEFAULT_MEGABYTES = 50;
    static final int MIN_MEGABYTES = 1;

    private final JdbcClient jdbc;
    private final LoreProperties properties;

    ImportSettings(JdbcClient jdbc, LoreProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** The ceiling of the server, in whole megabytes. */
    int ceilingMegabytes() {
        return (int) Math.max(MIN_MEGABYTES, properties.maxUploadSize().toMegabytes());
    }

    /** The size chosen by the administrators, if they chose one. */
    Optional<Integer> chosenMegabytes(UUID organizationId) {
        return jdbc.sql("select max_upload_mb from import_settings where organization_id = ?")
                .param(organizationId)
                .query((rs, row) -> (Integer) rs.getObject("max_upload_mb"))
                .optional()
                .filter(value -> value != null);
    }

    /** The size in force, in megabytes: the one chosen, or 50, never beyond the ceiling. */
    int megabytes(UUID organizationId) {
        return Math.min(chosenMegabytes(organizationId).orElse(DEFAULT_MEGABYTES), ceilingMegabytes());
    }

    long maxBytes(UUID organizationId) {
        return DataSize.ofMegabytes(megabytes(organizationId)).toBytes();
    }

    void choose(UUID organizationId, int megabytes) {
        jdbc.sql("""
                insert into import_settings (organization_id, max_upload_mb) values (?, ?)
                on conflict (organization_id) do update set max_upload_mb = excluded.max_upload_mb, updated_at = now()
                """)
                .params(organizationId, megabytes)
                .update();
    }
}
