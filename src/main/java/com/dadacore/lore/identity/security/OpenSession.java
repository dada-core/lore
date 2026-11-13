package com.dadacore.lore.identity.security;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A session listed in My account.
 *
 * @param id      an opaque handle derived from the session: the session identifier itself is a
 *                secret (it is the cookie value) and is never sent to the interface
 * @param browser for example "Chrome", or null when unknown
 * @param system  for example "Windows", or null when unknown
 */
public record OpenSession(
        String id,
        @Nullable String browser,
        @Nullable String system,
        @Nullable String ip,
        Instant signedInAt,
        Instant lastActiveAt,
        boolean current) {
}
