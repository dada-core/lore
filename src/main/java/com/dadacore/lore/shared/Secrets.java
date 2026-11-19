package com.dadacore.lore.shared;

/**
 * Keeps passwords, link tokens and setup codes out of the logs: the {@code toString()} of a
 * request that holds one writes {@link #hide(String)} in its place. Spring MVC writes the requests
 * it reads at the DEBUG level ({@code LORE_LOG_LEVEL=DEBUG}).
 */
public final class Secrets {

    private Secrets() {
    }

    /** {@code [hidden]}, or {@code null} when nothing was sent: the logs still tell them apart. */
    public static String hide(String secret) {
        return secret == null ? "null" : "[hidden]";
    }
}
