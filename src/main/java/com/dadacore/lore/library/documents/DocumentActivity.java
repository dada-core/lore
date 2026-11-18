package com.dadacore.lore.library.documents;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.dadacore.lore.audit.AuditLog;
import com.dadacore.lore.audit.AuditLog.Cursor;
import com.dadacore.lore.audit.AuditLog.Event;
import com.dadacore.lore.identity.OrganizationMembers;
import com.dadacore.lore.identity.Person;
import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.library.documents.DocumentViews.DocumentLink;
import com.dadacore.lore.library.documents.DocumentViews.SpaceLink;
import com.dadacore.lore.library.spaces.Space;
import com.dadacore.lore.library.spaces.Spaces;
import com.dadacore.lore.shared.Checks;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * What was done to the documents, read from the audit log once the right to see them is checked:
 * the activity of a document, and the recent activity of the home page. Never the views nor the
 * downloads; never the people chosen for an access, nor the title of a document the person may not
 * read.
 */
@Service
class DocumentActivity {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;
    /** The lines of the home page. */
    static final int HOME = 8;
    /**
     * The latest events of documents read for the home page, before those the person does not see
     * are left out: measured on a million events, reading them first is what keeps it fast.
     */
    static final int HOME_EVENTS = 2000;

    private final Documents documents;
    private final DocumentRepository repository;
    private final DocumentViews views;
    private final Spaces spaces;
    private final AuditLog audit;
    private final OrganizationMembers people;

    DocumentActivity(Documents documents, DocumentRepository repository, DocumentViews views, Spaces spaces, AuditLog audit,
            OrganizationMembers people) {
        this.documents = documents;
        this.repository = repository;
        this.views = views;
        this.spaces = spaces;
        this.audit = audit;
        this.people = people;
    }

    /**
     * Who acted.
     *
     * @param displayName null when the account no longer exists
     */
    record ActivityActor(UUID id, @Nullable String displayName) {
    }

    /**
     * An event of a document. The interface writes its sentence from its action and details.
     *
     * @param actor null for an operation of the system
     */
    record ActivityItem(long id, Instant occurredAt, @Nullable ActivityActor actor, String action, Map<String, Object> details) {
    }

    /** @param next the cursor of the following page; null on the last one */
    record ActivityPage(List<ActivityItem> items, @Nullable String next) {
    }

    /** An event of the home page: an import, a new version or a change of status. */
    record HomeItem(long id, Instant occurredAt, @Nullable ActivityActor actor, String action, DocumentLink document,
            SpaceLink space, Map<String, Object> details) {
    }

    /** Field errors {@code before:invalid_value}, {@code size:out_of_range}. */
    ActivityPage document(Viewer viewer, UUID id, @Nullable String before, @Nullable Integer size) {
        Documents.Opened opened = documents.open(viewer, id);
        Checks checks = Checks.start();
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
        List<Event> found = audit.activity(opened.space().organizationId(), "document", opened.row().id().toString(), cursor,
                limit + 1);
        List<Event> shown = found.size() > limit ? found.subList(0, limit) : found;
        Map<UUID, Person> names = names(shown);
        Map<UUID, DocumentLink> replacements = replacements(viewer, shown);
        List<ActivityItem> items = shown.stream()
                .map(event -> new ActivityItem(event.id(), event.occurredAt(), actor(names, event),
                        event.action(), details(event, replacements)))
                .toList();
        return new ActivityPage(items, found.size() > limit ? shown.getLast().cursor().value() : null);
    }

    /** The latest imports, new versions and changes of status of the documents the person sees, archived ones left out. */
    List<HomeItem> home(Viewer viewer) {
        List<Event> events = audit.documentEvents(viewer.organizationId(), HOME_EVENTS);
        Set<UUID> ids = new HashSet<>();
        for (Event event : events) {
            UUID id = uuid(event.resourceId());
            if (id != null) {
                ids.add(id);
            }
        }
        Map<UUID, DocumentRepository.Row> readable = new HashMap<>();
        for (DocumentRepository.Row row : repository.findAll(viewer.organizationId(), ids, documents.readable(viewer))) {
            if (!row.archived()) {
                readable.put(row.id(), row);
            }
        }
        List<Event> kept = new ArrayList<>();
        for (Event event : events) {
            UUID id = uuid(event.resourceId());
            if (id != null && readable.containsKey(id)) {
                kept.add(event);
                if (kept.size() == HOME) {
                    break;
                }
            }
        }
        Map<UUID, Person> names = names(kept);
        Map<UUID, SpaceLink> links = new LinkedHashMap<>();
        List<HomeItem> items = new ArrayList<>();
        for (Event event : kept) {
            DocumentRepository.Row row = readable.get(uuid(event.resourceId()));
            SpaceLink space = links.computeIfAbsent(row.spaceId(), spaceId -> {
                Space found = spaces.find(viewer, spaceId);
                return new SpaceLink(found.id(), found.name(), found.slug(), found.color());
            });
            Map<String, Object> details = new LinkedHashMap<>();
            copy(event, details, "version", "before", "after");
            items.add(new HomeItem(event.id(), event.occurredAt(), actor(names, event), event.action(),
                    new DocumentLink(row.id(), row.title()), space, details));
        }
        return items;
    }

    /**
     * The details a sentence needs, and nothing the person may not know: the number of people
     * chosen for an access, not who; the document that replaces another, only when they may read it.
     */
    private Map<String, Object> details(Event event, Map<UUID, DocumentLink> replacements) {
        Map<String, Object> details = new LinkedHashMap<>();
        switch (event.action()) {
            case "document.imported" -> copy(event, details, "filename", "size", "restricted");
            case "document.version_imported" -> copy(event, details, "version", "previous", "filename", "size", "status");
            case "document.status_changed" -> {
                copy(event, details, "before", "after", "version");
                UUID replacedBy = uuid(event.details().get("replacedBy"));
                if (replacedBy != null && replacements.containsKey(replacedBy)) {
                    details.put("replacedBy", replacements.get(replacedBy));
                }
            }
            case "document.updated" -> copy(event, details, "changes", "previousTitle", "previousLanguage");
            case "document.moved" -> {
                folder(event.details().get("from")).ifPresent(folder -> details.put("from", folder));
                folder(event.details().get("to")).ifPresent(folder -> details.put("to", folder));
            }
            case "document.access_changed" -> {
                copy(event, details, "before", "after");
                details.put("added", count(event.details().get("added")));
                details.put("removed", count(event.details().get("removed")));
            }
            case "document.owner_changed" -> copy(event, details, "fromName", "toName");
            default -> {
                // Nothing more than the action.
            }
        }
        return details;
    }

    /** The documents that replace others in these events, that the person may read now. */
    private Map<UUID, DocumentLink> replacements(Viewer viewer, Collection<Event> events) {
        Set<UUID> ids = new HashSet<>();
        for (Event event : events) {
            UUID id = uuid(event.details().get("replacedBy"));
            if (id != null) {
                ids.add(id);
            }
        }
        Map<UUID, DocumentLink> links = new HashMap<>();
        repository.findAll(viewer.organizationId(), ids, documents.readable(viewer))
                .forEach(row -> links.put(row.id(), new DocumentLink(row.id(), row.title())));
        return links;
    }

    /** A folder of a move: none at the root, its name, or only that it was deleted since. */
    private java.util.Optional<Object> folder(@Nullable Object id) {
        UUID folderId = uuid(id);
        if (folderId == null) {
            return java.util.Optional.empty();
        }
        DocumentViews.FolderLink folder = views.folder(folderId);
        return java.util.Optional.of(folder != null ? folder : Map.of("id", folderId, "deleted", true));
    }

    /** The names of who acted, also of those who left the organization since. */
    private Map<UUID, Person> names(Collection<Event> events) {
        Set<UUID> ids = new HashSet<>();
        events.forEach(event -> {
            if (event.actorId() != null) {
                ids.add(event.actorId());
            }
        });
        return ids.isEmpty() ? Map.of() : people.accounts(ids);
    }

    /** Null for the system; a person without a name when their account is gone. */
    private static @Nullable ActivityActor actor(Map<UUID, Person> names, Event event) {
        if (event.actorId() == null) {
            return null;
        }
        Person person = names.get(event.actorId());
        return new ActivityActor(event.actorId(), person == null ? null : person.displayName());
    }

    private static void copy(Event event, Map<String, Object> details, String... names) {
        for (String name : names) {
            Object value = event.details().get(name);
            if (value != null) {
                details.put(name, value);
            }
        }
    }

    private static int count(@Nullable Object list) {
        return list instanceof Collection<?> values ? values.size() : 0;
    }

    private static @Nullable UUID uuid(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
