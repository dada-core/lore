package com.dadacore.lore.audit;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The audit log of sensitive operations: sign-in failures, account and permission changes,
 * imports, views and downloads of documents...
 *
 * <p>Events are written in the caller's transaction: if the operation is rolled back, so is its
 * event. Nothing in the application updates an event; they are deleted after a year, every night.
 * This module knows no rule of access to documents: the activity of a document is read through the
 * module of the documents, which checks first that the person sees it.
 */
public interface AuditLog {

    void record(AuditEntry entry);

    /**
     * Records the entry, unless the same person did the same action on the same resource within
     * {@code within}: a page opened again, or reloaded, adds nothing.
     *
     * @return true when the entry was recorded
     */
    boolean recordOnce(AuditEntry entry, Duration within);

    /** The resources a person acted on last, most recent first, one line per resource. */
    List<Recent> recent(UUID organizationId, UUID actorId, String action, int limit);

    /**
     * What was done to a resource, the latest first, without its views and downloads: the activity
     * of a document.
     *
     * @param before the cursor of the last event of the previous page; null for the first
     */
    List<Event> activity(UUID organizationId, String resourceType, String resourceId, @Nullable Cursor before, int limit);

    /** The latest imports, new versions and changes of status of the documents of the organization. */
    List<Event> documentEvents(UUID organizationId, int limit);

    /** A page of the events of the organization that pass the filter, the latest first. */
    List<Event> events(UUID organizationId, Filter filter, @Nullable Cursor before, int limit);

    /**
     * Every event of the organization that passes the filter, the latest first, handed over one by
     * one as they are read from the database, a thousand at a time: an export holds none in memory.
     *
     * @return the number of events handed over
     */
    long eachEvent(UUID organizationId, Filter filter, Consumer<Event> each);

    /**
     * Which events: of a category of {@link AuditCategories}, by a person or by the system, within a
     * period; null, no condition.
     *
     * @param system the events without a person, made by the server
     */
    record Filter(@Nullable String category, @Nullable UUID actorId, boolean system, @Nullable Instant from,
            @Nullable Instant to) {
    }

    /** @param resourceId as recorded: the identifier of a document, for example */
    record Recent(String resourceId, Instant at) {
    }

    /**
     * An event, as read.
     *
     * @param actorId null for an operation of the system, for example a server command
     */
    record Event(long id, Instant occurredAt, @Nullable UUID actorId, String action, @Nullable String resourceType,
            @Nullable String resourceId, @Nullable String ip, Map<String, Object> details) {

        public Cursor cursor() {
            return new Cursor(occurredAt, id);
        }
    }

    /**
     * Where a page of events goes on: the date and the identifier of the last event shown. Written
     * {@code <microseconds since 1970>-<id>}, the precision of PostgreSQL.
     */
    record Cursor(Instant occurredAt, long id) {

        private static final Pattern FORM = Pattern.compile("(-?\\d{1,19})-(\\d{1,19})");
        /** The year 10000, in microseconds: no event is recorded later, nor before 1970. */
        private static final long LAST = 253_402_300_800_000_000L;

        /** The cursor written by {@link #value()}; null when it is not one. */
        public static @Nullable Cursor parse(String value) {
            Matcher matcher = FORM.matcher(value.strip());
            if (!matcher.matches()) {
                return null;
            }
            try {
                long micros = Long.parseLong(matcher.group(1));
                if (micros < 0 || micros >= LAST) {
                    return null;
                }
                Instant at = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000L);
                return new Cursor(at, Long.parseLong(matcher.group(2)));
            } catch (NumberFormatException | java.time.DateTimeException e) {
                return null;
            }
        }

        public String value() {
            long micros = occurredAt.getEpochSecond() * 1_000_000L + occurredAt.getNano() / 1000;
            return micros + "-" + id;
        }
    }
}
