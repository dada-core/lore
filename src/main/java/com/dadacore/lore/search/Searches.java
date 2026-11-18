package com.dadacore.lore.search;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.shared.ApiException;
import com.dadacore.lore.shared.Checks;

/**
 * Checks what is typed, asks {@link SearchIndex}, and shapes the results. A person runs two searches
 * at once at most, on this instance: the interface cancels the previous one before it asks again.
 */
@Service
class Searches {

    static final int MAX_LENGTH = 500;
    static final int MAX_WORDS = 32;
    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;
    /** 1,000 documents at most, whatever the size of the pages: beyond, a more precise search is better. */
    static final int MAX_DOCUMENTS = 1000;
    static final int MAX_TITLE_LENGTH = 100;
    static final int DEFAULT_TITLES = 8;
    static final int MAX_TITLES = 12;
    static final int AT_ONCE = 2;
    /** The words the reader highlights, at most. */
    static final int MAX_WORDS_FOUND = 20;

    /** The values of the filters, as the API of the documents writes them. */
    static final Set<String> CATEGORIES = Set.of("RUNBOOK", "OPERATING_PROCEDURE", "INCIDENT_REPORT", "SECURITY",
            "VENDOR", "ARCHITECTURE", "TECHNICAL");
    static final Set<String> STATUSES = Set.of("DRAFT", "PUBLISHED", "OBSOLETE", "ARCHIVED");
    static final Set<String> FORMATS = Set.of("application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/markdown", "text/plain");

    /** A web address pasted with its scheme: PostgreSQL would cut it at the colon. */
    private static final Pattern SCHEME = Pattern.compile("(?i)(?<![\\w.+-])[a-z][a-z0-9+.-]{1,15}://");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cc}");

    private final SearchIndex index;
    private final Clock clock;
    private final Map<UUID, Integer> running = new ConcurrentHashMap<>();

    Searches(SearchIndex index, Clock clock) {
        this.index = index;
        this.clock = clock;
    }

    /** What the routes receive, before any check. */
    record Asked(@Nullable String q, @Nullable List<UUID> spaces, @Nullable List<String> categories,
            @Nullable List<String> statuses, @Nullable List<String> formats, @Nullable String updated,
            @Nullable Boolean includeArchived, @Nullable Integer page, @Nullable Integer size) {
    }

    SearchViews.SearchPage search(Viewer viewer, Asked asked) {
        Checks checks = Checks.start();
        String text = query(checks, asked.q(), MAX_LENGTH);
        if (text != null && SPACES.split(text).length > MAX_WORDS) {
            checks.fail("q", "too_many_words", Map.of("max", MAX_WORDS));
        }
        Set<String> categories = values(checks, "category", asked.categories(), CATEGORIES, true);
        Set<String> statuses = values(checks, "status", asked.statuses(), STATUSES, true);
        Set<String> formats = values(checks, "format", asked.formats(), FORMATS, false);
        Instant since = null;
        if (asked.updated() != null && !asked.updated().isBlank()) {
            since = switch (asked.updated().strip()) {
                case "30d" -> clock.instant().minusSeconds(30L * 24 * 3600);
                case "12m" -> clock.instant().atZone(ZoneOffset.UTC).minusMonths(12).toInstant();
                default -> {
                    checks.fail("updated", "invalid_value");
                    yield null;
                }
            };
        }
        int size = asked.size() == null ? DEFAULT_SIZE : asked.size();
        if (size < 1 || size > MAX_SIZE) {
            checks.fail("size", "out_of_range", Map.of("min", 1, "max", MAX_SIZE));
        }
        int page = asked.page() == null ? 0 : asked.page();
        int lastPage = MAX_DOCUMENTS / Math.clamp(size, 1, MAX_SIZE) - 1;
        if (page < 0 || page > lastPage) {
            checks.fail("page", "out_of_range", Map.of("min", 0, "max", lastPage));
        }
        checks.orThrow();
        Split split = split(prepare(text));
        if (split.text().isBlank()) {
            // Only words to leave out: everything else would be found.
            throw Checks.rejected("q", "exclusions_only");
        }
        Set<UUID> spaces = asked.spaces() == null ? Set.of() : new HashSet<>(asked.spaces());
        SearchIndex.Request request = new SearchIndex.Request(split.text(), terms(split.text()), split.excluded(), spaces, categories,
                statuses, formats, since, Boolean.TRUE.equals(asked.includeArchived()), page, size);
        SearchIndex.Found found = once(viewer, () -> index.search(viewer, request));
        return SearchViews.page(found, page, size);
    }

    List<SearchViews.TitleResult> titles(Viewer viewer, @Nullable String q, @Nullable Integer size) {
        Checks checks = Checks.start();
        String text = query(checks, q, MAX_TITLE_LENGTH);
        int limit = size == null ? DEFAULT_TITLES : size;
        if (limit < 1 || limit > MAX_TITLES) {
            checks.fail("size", "out_of_range", Map.of("min", 1, "max", MAX_TITLES));
        }
        checks.orThrow();
        String typed = prepare(text);
        List<SearchIndex.FoundTitle> found = once(viewer, () -> index.titles(viewer, typed, limit));
        return SearchViews.titles(found, typed);
    }

    /** Required, and not longer than {@code max}; nothing of it is ever logged. */
    private static @Nullable String query(Checks checks, @Nullable String q, int max) {
        if (q == null || q.isBlank()) {
            checks.fail("q", "required");
            return null;
        }
        // Control characters are no text to search for (NUL is refused by the driver of PostgreSQL).
        String text = CONTROL.matcher(q).replaceAll(" ").strip();
        if (text.isEmpty()) {
            checks.fail("q", "required");
            return null;
        }
        if (text.length() > max) {
            checks.fail("q", "too_long", Map.of("max", max));
        }
        return text;
    }

    /** What is typed, without the words left out; and those words, as typed. */
    record Split(String text, List<String> excluded) {
    }

    /**
     * The words left out: a {@code -} at the start of a word or of a phrase, after a space. Inside a
     * word ({@code kube-system}) or doubled ({@code --no-owner}), it is part of what is searched.
     */
    static Split split(String text) {
        StringBuilder kept = new StringBuilder(text.length());
        List<String> excluded = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            boolean start = i == 0 || Character.isWhitespace(text.charAt(i - 1));
            if (c == '-' && start && i + 1 < text.length() && text.charAt(i + 1) != '-'
                    && !Character.isWhitespace(text.charAt(i + 1))) {
                int end;
                if (text.charAt(i + 1) == '"') {
                    int close = text.indexOf('"', i + 2);
                    end = close < 0 ? text.length() : close + 1;
                } else {
                    end = i + 1;
                    while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
                        end++;
                    }
                }
                String term = text.substring(i + 1, end).strip();
                if (!term.isEmpty() && !term.equals("\"\"")) {
                    excluded.add(term);
                }
                i = end;
                continue;
            }
            if (c == '"') {
                // A phrase is searched as it is written: a dash inside it leaves nothing out.
                int close = text.indexOf('"', i + 1);
                int end = close < 0 ? text.length() : close + 1;
                kept.append(text, i, end);
                i = end;
                continue;
            }
            kept.append(c);
            i++;
        }
        return new Split(SPACES.matcher(kept.toString()).replaceAll(" ").strip(), List.copyOf(excluded));
    }

    /**
     * The terms of what is typed, each of which a document must hold somewhere: a word, a phrase
     * between quotes, or words joined by {@code or} ({@code pg_restore or pg_dump}). An {@code or}
     * at the start or at the end joins nothing, and is left out.
     */
    static List<String> terms(String text) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            if (Character.isWhitespace(text.charAt(i))) {
                i++;
                continue;
            }
            int end;
            if (text.charAt(i) == '"') {
                int close = text.indexOf('"', i + 1);
                end = close < 0 ? text.length() : close + 1;
            } else {
                end = i;
                while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
                    end++;
                }
            }
            tokens.add(text.substring(i, end));
            i = end;
        }
        List<String> terms = new ArrayList<>();
        boolean join = false;
        for (String token : tokens) {
            if (token.equalsIgnoreCase("or")) {
                join = !terms.isEmpty();
                continue;
            }
            if (join) {
                terms.set(terms.size() - 1, terms.getLast() + " or " + token);
            } else {
                terms.add(token);
            }
            join = false;
        }
        return List.copyOf(terms);
    }

    /** A web address is searched without its scheme ({@code https://}), the way it is indexed. */
    static String prepare(String text) {
        return SCHEME.matcher(text).replaceAll("").strip();
    }

    private static Set<String> values(Checks checks, String field, @Nullable List<String> asked, Set<String> allowed,
            boolean upper) {
        if (asked == null || asked.isEmpty()) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String value : asked) {
            String v = value == null ? "" : upper ? value.strip().toUpperCase(java.util.Locale.ROOT) : value.strip();
            if (!allowed.contains(v)) {
                checks.fail(field, "invalid_value");
                return Set.of();
            }
            values.add(v);
        }
        return values;
    }

    /** Two searches at once at most for a person: {@code 429 too_many_searches} beyond. */
    private <T> T once(Viewer viewer, java.util.function.Supplier<T> search) {
        UUID person = viewer.userId();
        Integer now = running.merge(person, 1, Integer::sum);
        try {
            if (now > AT_ONCE) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_searches");
            }
            return search.get();
        } finally {
            running.computeIfPresent(person, (key, count) -> count <= 1 ? null : count - 1);
        }
    }

    static List<String> words(List<String> title, List<String> excerpt) {
        List<String> words = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<String> from : List.of(title, excerpt)) {
            for (String word : from) {
                if (words.size() < MAX_WORDS_FOUND && seen.add(word.toLowerCase(java.util.Locale.ROOT))) {
                    words.add(word);
                }
            }
        }
        return words;
    }
}
