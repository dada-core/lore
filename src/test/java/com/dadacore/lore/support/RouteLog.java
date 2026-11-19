package com.dadacore.lore.support;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The requests a matrix of rights sends while it probes, so that it proves it probes every route it
 * answers for (see {@link RouteMatrix}). Only the probes count: the requests that prepare the data
 * prove nothing about the rights.
 */
public final class RouteLog {

    private static final Map<Class<?>, Set<String>> PROBED = new ConcurrentHashMap<>();
    private static final ThreadLocal<Class<?>> PROBING = new ThreadLocal<>();

    private RouteLog() {
    }

    /** Runs a probe of this matrix, its requests recorded. */
    public static <T> T probe(Class<?> matrix, Supplier<T> call) {
        PROBING.set(matrix);
        try {
            return call.get();
        } finally {
            PROBING.remove();
        }
    }

    /** Called by {@link Browser} for each request it sends. */
    static void record(String method, String path) {
        Class<?> matrix = PROBING.get();
        if (matrix != null) {
            int query = path.indexOf('?');
            PROBED.computeIfAbsent(matrix, key -> ConcurrentHashMap.newKeySet())
                    .add(method + " " + (query < 0 ? path : path.substring(0, query)));
        }
    }

    /** The requests the probes of this matrix sent: {@code GET /api/v1/spaces/0193...}. */
    public static Set<String> probed(Class<?> matrix) {
        return Set.copyOf(PROBED.getOrDefault(matrix, Set.of()));
    }
}
