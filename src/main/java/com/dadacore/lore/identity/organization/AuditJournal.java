package com.dadacore.lore.identity.organization;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.dadacore.lore.audit.AuditCategories;
import com.dadacore.lore.audit.AuditEntry;
import com.dadacore.lore.audit.AuditLog;
import com.dadacore.lore.audit.AuditLog.Cursor;
import com.dadacore.lore.audit.AuditLog.Event;
import com.dadacore.lore.identity.OrganizationMembers;
import com.dadacore.lore.identity.Person;
import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.shared.ApiException;
import com.dadacore.lore.shared.Checks;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Administration › Audit log: the events of the organization, filtered by category, person and
 * period, page by page, and their export in CSV. Reserved to owners and administrators
 * ({@code 403 admin_only}). The names of the people are read when shown, also for those who left.
 */
@Service
class AuditJournal {

    static final int DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 200;
    /** The events made by the server, without a person: a command, for example. */
    static final String SYSTEM = "system";

    private static final Logger log = LoggerFactory.getLogger(AuditJournal.class);

    private final AuditLog audit;
    private final OrganizationMembers people;
    private final JsonMapper json;
    /** The people exporting the log now: one export at a time each. */
    private final Set<UUID> exporting = ConcurrentHashMap.newKeySet();

    AuditJournal(AuditLog audit, OrganizationMembers people, JsonMapper json) {
        this.audit = audit;
        this.people = people;
        this.json = json;
    }

    /** @param displayName null when the account no longer exists */
    record AuditActor(UUID id, @Nullable String displayName, @Nullable String email) {
    }

    record AuditResource(String type, String id) {
    }

    /**
     * An event of the log. The interface writes its sentence from its action and details, in the
     * language of the person reading.
     *
     * @param actor    null for an operation of the system
     * @param category {@code view}, {@code download}, {@code import}, {@code status}, {@code change},
     *                 {@code deletion}, {@code rights}, {@code members}, {@code sign_in},
     *                 {@code organization}, or {@code other} for an action this version does not know
     */
    record AuditItem(long id, Instant occurredAt, @Nullable AuditActor actor, String action, String category,
            @Nullable AuditResource resource, @Nullable String ip, Map<String, Object> details) {
    }

    /** @param next the cursor of the following page; null on the last one */
    record AuditPage(List<AuditItem> items, @Nullable String next) {
    }

    /**
     * Field errors {@code category}, {@code actor}, {@code from}, {@code to}, {@code before}
     * ({@code invalid_value}) and {@code size} ({@code out_of_range}).
     */
    AuditPage page(Viewer viewer, @Nullable String category, @Nullable String actor, @Nullable String from, @Nullable String to,
            @Nullable String before, @Nullable Integer size) {
        viewer.requireAdmin();
        Checks checks = Checks.start();
        AuditLog.Filter filter = filter(checks, category, actor, from, to);
        Cursor cursor = null;
        if (before != null && !before.isBlank()) {
            cursor = Cursor.parse(before);
            if (cursor == null) {
                checks.fail("before", "invalid_value");
            }
        }
        int limit = size == null ? DEFAULT_SIZE : size;
        if (limit < 1 || limit > MAX_SIZE) {
            checks.fail("size", "out_of_range", Map.of("min", 1, "max", MAX_SIZE));
        }
        checks.orThrow();
        List<Event> found = audit.events(viewer.organizationId(), filter, cursor, limit + 1);
        List<Event> shown = found.size() > limit ? found.subList(0, limit) : found;
        Set<UUID> actors = new HashSet<>();
        shown.forEach(event -> {
            if (event.actorId() != null) {
                actors.add(event.actorId());
            }
        });
        Map<UUID, Person> names = actors.isEmpty() ? Map.of() : people.accounts(actors);
        List<AuditItem> items = shown.stream().map(event -> item(event, names)).toList();
        return new AuditPage(items, found.size() > limit ? shown.getLast().cursor().value() : null);
    }

    /**
     * The events of the filters, in CSV (RFC 4180, UTF-8 with a byte order mark for spreadsheets),
     * written as they are read; the export itself is recorded. A value that a spreadsheet would read
     * as a formula ({@code =}, {@code +}, {@code -}, {@code @}) is written after an apostrophe.
     * {@code 429 export_running} while the same person exports already.
     */
    void export(Viewer viewer, @Nullable String category, @Nullable String actor, @Nullable String from,
            @Nullable String to, HttpServletResponse response) throws IOException {
        viewer.requireAdmin();
        Checks checks = Checks.start();
        AuditLog.Filter filter = filter(checks, category, actor, from, to);
        checks.orThrow();
        if (!exporting.add(viewer.userId())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "export_running");
        }
        java.util.concurrent.atomic.AtomicLong rows = new java.util.concurrent.atomic.AtomicLong();
        try {
            response.setContentType("text/csv;charset=UTF-8");
            response.setHeader("Content-Disposition", ContentDisposition.attachment()
                    .filename("lore-audit-" + LocalDate.now(ZoneOffset.UTC) + ".csv").build().toString());
            response.setHeader("Cache-Control", "no-store");
            Writer out = new BufferedWriter(new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8));
            out.write('\uFEFF');
            line(out, List.of("occurred_at", "actor_id", "actor_name", "actor_email", "action", "category", "resource_type",
                    "resource_id", "title", "space", "ip", "details"));
            // A name read once per person, also when the account is gone.
            Map<UUID, java.util.Optional<Person>> names = new HashMap<>();
            audit.eachEvent(viewer.organizationId(), filter, event -> {
                Person person = event.actorId() == null ? null
                        : names.computeIfAbsent(event.actorId(), id -> java.util.Optional.ofNullable(people.accounts(List.of(id)).get(id)))
                                .orElse(null);
                List<String> values = new ArrayList<>();
                values.add(event.occurredAt().toString());
                values.add(event.actorId() == null ? "" : event.actorId().toString());
                values.add(person == null ? "" : person.displayName());
                values.add(person == null ? "" : person.email());
                values.add(event.action());
                values.add(AuditCategories.of(event.action()));
                values.add(event.resourceType());
                values.add(event.resourceId());
                values.add(text(event.details().get("title")));
                values.add(text(event.details().get("space")));
                values.add(event.ip());
                values.add(json.writeValueAsString(event.details()));
                try {
                    line(out, values);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                rows.incrementAndGet();
            });
            out.flush();
        } catch (UncheckedIOException | IOException stopped) {
            // The browser stopped reading: an export cancelled. What was written is recorded below.
            log.debug("Export of the audit log stopped: {}", stopped.toString());
        } finally {
            exporting.remove(viewer.userId());
            audit.record(AuditEntry.of(viewer.organizationId(), viewer.userId(), "audit.exported")
                    .ip(viewer.ip())
                    .detail("category", filter.category())
                    .detail("actor", filter.system() ? SYSTEM : filter.actorId())
                    .detail("from", filter.from() == null ? null : filter.from().toString())
                    .detail("to", filter.to() == null ? null : filter.to().toString())
                    .detail("rows", rows.get())
                    .build());
        }
    }

    private static AuditLog.Filter filter(Checks checks, @Nullable String category, @Nullable String actor,
            @Nullable String from, @Nullable String to) {
        String shown = null;
        if (category != null && !category.isBlank()) {
            shown = category.strip();
            if (!AuditCategories.exists(shown)) {
                checks.fail("category", "invalid_value");
                shown = null;
            }
        }
        UUID person = null;
        boolean system = false;
        if (actor != null && !actor.isBlank()) {
            if (SYSTEM.equals(actor.strip())) {
                system = true;
            } else {
                try {
                    person = UUID.fromString(actor.strip());
                } catch (IllegalArgumentException e) {
                    checks.fail("actor", "invalid_value");
                }
            }
        }
        return new AuditLog.Filter(shown, person, system, instant(checks, "from", from), instant(checks, "to", to));
    }

    private static @Nullable Instant instant(Checks checks, String field, @Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            checks.fail(field, "invalid_value");
            return null;
        }
    }

    private static AuditItem item(Event event, Map<UUID, Person> names) {
        AuditActor actor = null;
        if (event.actorId() != null) {
            Person person = names.get(event.actorId());
            actor = new AuditActor(event.actorId(), person == null ? null : person.displayName(),
                    person == null ? null : person.email());
        }
        AuditResource resource = event.resourceType() == null || event.resourceId() == null ? null
                : new AuditResource(event.resourceType(), event.resourceId());
        return new AuditItem(event.id(), event.occurredAt(), actor, event.action(), AuditCategories.of(event.action()), resource,
                event.ip(), event.details());
    }

    private static String text(@Nullable Object value) {
        return value == null ? "" : value.toString();
    }

    /** One record of CSV, ended by CRLF. */
    static void line(Writer out, List<String> values) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.write(',');
            }
            out.write(field(values.get(i)));
        }
        out.write("\r\n");
    }

    /**
     * A value of CSV: quoted when it must be, and never read as a formula by a spreadsheet. A value
     * holding a semicolon or a tab is quoted too: a spreadsheet set for French splits on them, and
     * would read what follows as a cell of its own.
     */
    static String field(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String written = "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        for (char c : new char[] {',', '"', '\n', '\r', ';', '\t'}) {
            if (written.indexOf(c) >= 0) {
                return '"' + written.replace("\"", "\"\"") + '"';
            }
        }
        return written;
    }
}
