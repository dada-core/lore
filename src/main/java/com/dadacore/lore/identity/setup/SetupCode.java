package com.dadacore.lore.identity.setup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

import com.dadacore.lore.LoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One-time code required by the initial setup, so that the first person to reach a fresh
 * installation cannot take it over. It is printed in the container logs
 * ({@code docker compose logs lore}), or set with {@code LORE_SETUP_CODE} for automated
 * installations.
 *
 * <p>After 10 wrong attempts, a random code is replaced by a new one, printed in the logs.
 */
@Component
class SetupCode {

    private static final Logger log = LoggerFactory.getLogger(SetupCode.class);
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int MAX_FAILURES = 10;

    private final SecureRandom random = new SecureRandom();
    private final boolean fromEnvironment;
    private String code;
    private int failures;

    SetupCode(LoreProperties properties) {
        String configured = properties.setupCode();
        this.fromEnvironment = configured != null && !configured.isBlank();
        this.code = fromEnvironment ? configured.strip() : generate();
    }

    synchronized String current() {
        return code;
    }

    boolean fromEnvironment() {
        return fromEnvironment;
    }

    synchronized boolean verify(String candidate) {
        boolean valid = MessageDigest.isEqual(
                normalize(code).getBytes(StandardCharsets.UTF_8),
                normalize(candidate).getBytes(StandardCharsets.UTF_8));
        if (valid) {
            return true;
        }
        failures++;
        if (failures >= MAX_FAILURES && !fromEnvironment) {
            failures = 0;
            code = generate();
            log.warn("Too many wrong setup codes. New setup code: {}", code);
        }
        return false;
    }

    private String generate() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) {
                builder.append('-');
            }
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }
}
