package com.dadacore.lore;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Settings read from the environment ({@code LORE_*} variables).
 *
 * @param publicUrl         address at which the team opens Lore ({@code LORE_PUBLIC_URL}).
 *                          When set, it takes precedence over the address entered at setup.
 * @param sessionTimeout    maximum inactivity before a session expires
 *                          ({@code LORE_SESSION_TIMEOUT}). Administrators can only lower it.
 * @param setupCode         fixed initial setup code ({@code LORE_SETUP_CODE}), for automated
 *                          installations: 12 characters or more, spaces and dashes left aside.
 *                          When empty, a random code is printed in the logs.
 * @param filesPath         folder of the imported files ({@code LORE_FILES_PATH})
 * @param maxUploadSize     ceiling of the size of an imported file ({@code LORE_MAX_UPLOAD_SIZE}),
 *                          1 GB at most. Administrators choose the size in force below it, 50 MB
 *                          by default.
 * @param extractionWorkers extractions run at the same time, each in its own process
 *                          ({@code LORE_EXTRACTION_WORKERS}), from 1 to 8
 * @param extractionTimeout longest extraction of one file ({@code LORE_EXTRACTION_TIMEOUT})
 * @param extractionMemory  memory of each extraction process ({@code LORE_EXTRACTION_MEMORY})
 */
@ConfigurationProperties("lore")
public record LoreProperties(
        URI publicUrl,
        @DefaultValue("8h") Duration sessionTimeout,
        String setupCode,
        @DefaultValue("/var/lib/lore/files") Path filesPath,
        @DefaultValue("1GB") DataSize maxUploadSize,
        @DefaultValue("2") int extractionWorkers,
        @DefaultValue("120s") Duration extractionTimeout,
        @DefaultValue("512MB") DataSize extractionMemory) {

    public static final DataSize MAX_UPLOAD_CEILING = DataSize.ofGigabytes(1);

    /** The fields and boundaries of an import, around its file: a request is never larger. */
    public static final DataSize UPLOAD_FORM_MARGIN = DataSize.ofMegabytes(1);

    /** The length of a random setup code, and the least a fixed one must have once normalized. */
    public static final int SETUP_CODE_MIN_LENGTH = 12;

    public LoreProperties {
        if (maxUploadSize.toBytes() < DataSize.ofMegabytes(1).toBytes() || maxUploadSize.compareTo(MAX_UPLOAD_CEILING) > 0) {
            throw new IllegalArgumentException("LORE_MAX_UPLOAD_SIZE must be between 1MB and 1GB, not " + maxUploadSize + ".");
        }
        if (extractionWorkers < 1 || extractionWorkers > 8) {
            throw new IllegalArgumentException("LORE_EXTRACTION_WORKERS must be between 1 and 8, not " + extractionWorkers + ".");
        }
        if (extractionTimeout.toSeconds() < 10) {
            throw new IllegalArgumentException("LORE_EXTRACTION_TIMEOUT must be 10s or more, not " + extractionTimeout + ".");
        }
        if (extractionMemory.toMegabytes() < 128) {
            throw new IllegalArgumentException("LORE_EXTRACTION_MEMORY must be 128MB or more, not " + extractionMemory + ".");
        }
    }

    /** A setup code as it is compared: without spaces nor dashes, in capitals. */
    public static String normalizeSetupCode(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }
}
