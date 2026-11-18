package com.dadacore.lore.library.documents;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.dadacore.lore.access.DocumentAccess;
import com.dadacore.lore.access.DocumentGrants;
import com.dadacore.lore.access.DocumentRef;
import com.dadacore.lore.access.SpaceMembers;
import com.dadacore.lore.access.SpaceRole;
import com.dadacore.lore.audit.AuditEntry;
import com.dadacore.lore.audit.AuditLog;
import com.dadacore.lore.identity.OrganizationMember;
import com.dadacore.lore.identity.OrganizationMembers;
import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.ingestion.ExtractionResults;
import com.dadacore.lore.ingestion.ExtractionQueue;
import com.dadacore.lore.library.documents.DocumentViews.DocumentActions;
import com.dadacore.lore.library.documents.DocumentViews.DocumentItem;
import com.dadacore.lore.library.documents.DocumentViews.DocumentView;
import com.dadacore.lore.library.spaces.Space;
import com.dadacore.lore.library.spaces.Spaces;
import com.dadacore.lore.shared.ApiException;
import com.dadacore.lore.shared.Checks;
import com.dadacore.lore.shared.PageQuery;
import com.dadacore.lore.shared.PageResponse;
import com.dadacore.lore.shared.SqlCondition;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The documents of the spaces, once imported: lists, the page of a document, its title,
 * description and category, its folder, its access, its status, its versions, its deletion, and a
 * new attempt at its extraction.
 *
 * <p>Every read adds the condition of {@link DocumentAccess}: a document the person cannot see
 * appears nowhere, not even in a count. Every change locks the space first, like the folders, and
 * an archived space refuses it ({@code 409 space_archived}). An archived document accepts only a
 * change of its status and its deletion ({@code 409 document_archived}).
 */
@Service
public class Documents {

    static final int MAX_DESCRIPTION = 2000;
    /** The languages a document is indexed in. */
    static final Set<String> LANGUAGES = Set.of("fr", "en", "other");
    private static final java.util.regex.Pattern CONTROL = java.util.regex.Pattern.compile("\\p{Cc}");
    private static final java.util.regex.Pattern CONTROL_BUT_LINES = java.util.regex.Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");
    static final int MAX_IDS = 100;
    static final int ATTENTION = 20;
    static final Set<String> PASSING_FAILURES = ExtractionResults.PASSING_FAILURES;
    /** A page opened again within the hour is the same view: decision 25 of the reader. */
    static final Duration VIEW_ONCE = Duration.ofHours(1);
    static final String VIEWED = "document.viewed";
    /** "Resume" on the home page: 3 documents by default, 10 at most. */
    static final int RECENT = 3;
    static final int MAX_RECENT = 10;
    /** The views looked at for "Resume", before the documents the person no longer sees are left out. */
    static final int RECENT_VIEWS = 50;

    /** The categories of a document. */
    enum Category {
        RUNBOOK, OPERATING_PROCEDURE, INCIDENT_REPORT, SECURITY, VENDOR, ARCHITECTURE, TECHNICAL
    }

    /** The statuses a document can be imported with, or take with a new version. */
    enum ImportStatus {
        DRAFT, PUBLISHED
    }

    /** The statuses of a document. */
    enum Status {
        DRAFT, PUBLISHED, OBSOLETE, ARCHIVED
    }

    private final DocumentRepository documents;
    private final DocumentViews views;
    private final Spaces spaces;
    private final DocumentAccess access;
    private final DocumentGrants grants;
    private final SpaceMembers members;
    private final OrganizationMembers people;
    private final ExtractionQueue queue;
    private final FileJanitor janitor;
    private final DocumentLocks locks;
    private final AuditLog audit;

    Documents(DocumentRepository documents, DocumentViews views, Spaces spaces, DocumentAccess access,
            DocumentGrants grants, SpaceMembers members, OrganizationMembers people, ExtractionQueue queue,
            FileJanitor janitor, DocumentLocks locks, AuditLog audit) {
        this.documents = documents;
        this.views = views;
        this.spaces = spaces;
        this.access = access;
        this.grants = grants;
        this.members = members;
        this.people = people;
        this.queue = queue;
        this.janitor = janitor;
        this.locks = locks;
        this.audit = audit;
    }

    /**
     * The changes of a document; a field left out keeps its value, an empty one is removed.
     *
     * @param language {@code fr}, {@code en} or {@code other}: the document is indexed again in it
     */
    record DocumentChanges(@Nullable String title, @Nullable String description, @Nullable String category,
            @Nullable String language) {
    }

    /** {@code folderId} null: to the root of the space. */
    record DocumentMove(@Nullable UUID folderId) {
    }

    /** The people who see a restricted document, and why. */
    record AccessView(boolean restricted, int spaceMembers, List<AccessPerson> people) {
    }

    /** @param reasons {@code chosen}, {@code maintainer} (of the space) and {@code owner} (of the document) */
    record AccessPerson(UUID id, String displayName, String email, List<String> reasons) {
    }

    record AccessChange(@Nullable Boolean restricted, @Nullable List<UUID> userIds) {
    }

    /**
     * The counts of the documents the person sees in a space.
     *
     * @param archived the archived documents, apart: they count nowhere else, and only the
     *                 maintainers see them
     */
    record DocumentSummary(long total, long inProgress, long scanned, long failed, long archived,
            List<FolderDocuments> folders) {
    }

    /**
     * A new status; {@code replacedBy}, for Obsolete only, the document that replaces it, which the
     * person may read.
     */
    record StatusChange(@Nullable String status, @Nullable UUID replacedBy) {
    }

    /** {@code folderId} null: the documents at the root of the space. */
    record FolderDocuments(@Nullable UUID folderId, long count) {
    }

    /** @param userId the new person in charge of the document */
    record OwnerChange(@Nullable UUID userId) {
    }

    /** A document the person opened lately, for "Resume" on the home page. */
    record RecentDocument(UUID id, String title, DocumentViews.SpaceLink space, DocumentViews.@Nullable FolderLink folder,
            String status, Instant viewedAt) {
    }

    /**
     * A version imported by the person, still in progress or failed, for the home page: a first
     * import, or a new version of a document.
     */
    record AttentionItem(UUID id, String title, DocumentViews.SpaceLink space, int version, String extractionStatus,
            @Nullable String extractionError) {
    }

    PageResponse<DocumentItem> list(Viewer viewer, UUID spaceId, @Nullable String folder, @Nullable Boolean restricted,
            @Nullable List<UUID> ids, @Nullable List<String> statuses, @Nullable String sort, @Nullable String direction,
            PageQuery page) {
        Space space = spaces.open(viewer, spaceId, SpaceRole.READER).space();
        Checks checks = Checks.start();
        boolean root = "none".equals(folder);
        UUID folderId = null;
        if (folder != null && !root) {
            try {
                folderId = UUID.fromString(folder);
            } catch (IllegalArgumentException e) {
                checks.fail("folder", "invalid_value");
            }
        }
        List<UUID> chosen = ids == null ? List.of() : ids;
        if (chosen.size() > MAX_IDS) {
            checks.fail("ids", "too_many", Map.of("max", MAX_IDS));
        }
        List<String> shown = new ArrayList<>();
        for (String status : statuses == null ? List.<String>of() : statuses) {
            Status value = checks.oneOf("status", status.toUpperCase(Locale.ROOT), Status.class);
            if (value != null && !shown.contains(value.name())) {
                shown.add(value.name());
            }
        }
        DocumentRepository.Sort order = sort == null ? DocumentRepository.Sort.UPDATED
                : checks.oneOf("sort", sort.toUpperCase(Locale.ROOT), DocumentRepository.Sort.class);
        Boolean descending = null;
        if (direction != null) {
            if (direction.equals("asc") || direction.equals("desc")) {
                descending = direction.equals("desc");
            } else {
                checks.fail("direction", "invalid_value");
            }
        }
        checks.orThrow();
        // The latest first; titles, categories, statuses and sizes in their order.
        boolean desc = descending != null ? descending : order == DocumentRepository.Sort.UPDATED;
        DocumentRepository.Filter filter = new DocumentRepository.Filter(folderId, root, restricted, chosen, shown);
        SqlCondition readable = readable(viewer);
        List<DocumentRepository.Row> rows = documents.list(space.id(), readable, filter, order, desc, page.offset(),
                page.size());
        Map<UUID, DocumentRepository.Version> newer = new HashMap<>();
        for (DocumentRepository.Row row : rows) {
            if (row.latestNumber() > row.version().number()) {
                newer.put(row.id(), documents.latest(row.id()));
            }
        }
        return new PageResponse<>(views.items(space.organizationId(), rows, newer), page.page(), page.size(),
                documents.count(space.id(), readable, filter));
    }

    DocumentSummary summary(Viewer viewer, UUID spaceId) {
        Space space = spaces.open(viewer, spaceId, SpaceRole.READER).space();
        DocumentRepository.Summary summary = documents.summary(space.id(), readable(viewer));
        return new DocumentSummary(summary.total(), summary.inProgress(), summary.scanned(), summary.failed(),
                summary.archived(), summary.folders().stream().map(f -> new FolderDocuments(f.folderId(), f.count())).toList());
    }

    /**
     * The page of a document: its opening is a view, recorded once an hour at most, with the version
     * shown ({@code version}, an older one; the current one by default).
     */
    DocumentView get(Viewer viewer, UUID id, @Nullable Integer version) {
        Opened opened = open(viewer, id);
        int shown = version != null && version >= 1 && version <= opened.row().latestNumber() ? version
                : opened.row().version().number();
        audit.recordOnce(entry(viewer, opened, VIEWED).detail("version", shown).build(), VIEW_ONCE);
        return view(viewer, opened, null);
    }

    /**
     * Field errors {@code title:required}, {@code title:too_long}, {@code description:too_long},
     * {@code category:invalid_value}, {@code language:invalid_value}; {@code 409 extraction_pending}
     * for a language changed while the text is extracted.
     */
    @Transactional
    DocumentView update(Viewer viewer, UUID id, DocumentChanges changes) {
        Opened opened = writable(openForChange(viewer, id, SpaceRole.CONTRIBUTOR));
        DocumentRepository.Row row = opened.row();
        Checks checks = Checks.start();
        // Control characters out: they would read as the marks of the words found by the search.
        String title = changes.title() == null ? row.title()
                : checks.text("title", CONTROL.matcher(changes.title()).replaceAll(" "), Titles.MAX_TITLE);
        String description = row.description();
        if (changes.description() != null) {
            String written = CONTROL_BUT_LINES.matcher(changes.description()).replaceAll(" ").strip();
            description = written.isEmpty() ? null : written;
            if (description != null && description.length() > MAX_DESCRIPTION) {
                checks.fail("description", "too_long", Map.of("max", MAX_DESCRIPTION));
            }
        }
        String category = row.category();
        if (changes.category() != null) {
            category = changes.category().isBlank() ? null : name(checks.oneOf("category", changes.category(), Category.class));
        }
        DocumentRepository.Version version = row.version();
        String language = version.language();
        if (changes.language() != null) {
            language = changes.language().strip().toLowerCase(Locale.ROOT);
            if (!LANGUAGES.contains(language)) {
                checks.fail("language", "invalid_value");
            }
        }
        checks.orThrow();
        boolean relanguaged = !Objects.equals(language, version.language());
        if (relanguaged && "PENDING".equals(version.extractionStatus())) {
            // Detected at the end of the extraction, which would also write the passages again.
            throw ApiException.conflict("extraction_pending");
        }
        List<String> changed = new ArrayList<>();
        if (!title.equals(row.title())) {
            changed.add("title");
        }
        if (!Objects.equals(description, row.description())) {
            changed.add("description");
        }
        if (!Objects.equals(category, row.category())) {
            changed.add("category");
        }
        if (relanguaged) {
            changed.add("language");
        }
        if (!changed.isEmpty()) {
            documents.update(row.id(), title, description, category);
            if (relanguaged) {
                documents.changeLanguage(row.id(), version.id(), language);
            }
            spaces.touch(opened.space().id());
            audit.record(entry(viewer, opened, "document.updated")
                    .detail("changes", changed)
                    .detail("previousTitle", changed.contains("title") ? row.title() : null)
                    .detail("previousLanguage", relanguaged ? version.language() : null)
                    .build());
        }
        return view(viewer, reload(viewer, opened), null);
    }

    /** Field error {@code folderId:unknown_folder}, the same for a folder of another space. */
    @Transactional
    DocumentView move(Viewer viewer, UUID id, DocumentMove move) {
        Opened opened = writable(openForChange(viewer, id, SpaceRole.CONTRIBUTOR));
        UUID folderId = move.folderId();
        if (folderId != null && !documents.folderOf(opened.space().id(), folderId)) {
            throw Checks.rejected("folderId", "unknown_folder");
        }
        if (!Objects.equals(folderId, opened.row().folderId())) {
            documents.move(opened.row().id(), folderId);
            spaces.touch(opened.space().id());
            audit.record(entry(viewer, opened, "document.moved")
                    .detail("from", opened.row().folderId())
                    .detail("to", folderId)
                    .build());
        }
        return view(viewer, reload(viewer, opened), null);
    }

    /** Deletes the document, its versions, its text and its access, then its file if nothing else uses it. */
    @Transactional
    void delete(Viewer viewer, UUID id) {
        Opened opened = openForChange(viewer, id, SpaceRole.READER);
        if (!access.canDelete(viewer, opened.ref(), opened.role())) {
            throw ApiException.forbidden("maintainer_only");
        }
        List<String> contents = documents.contents(opened.row().id(), null);
        locks.forDeletion(opened.row().id(), null);
        documents.delete(opened.row().id());
        spaces.touch(opened.space().id());
        audit.record(entry(viewer, opened, "document.deleted")
                .detail("versions", opened.row().versionCount())
                .detail("filename", opened.row().version().filename())
                .build());
        janitor.afterCommit(opened.space().organizationId(), contents);
    }

    /**
     * A new attempt at the latest version, the current one or a newer one. {@code 409 not_retryable}
     * for a final failure, or a version that did not fail.
     */
    @Transactional
    DocumentView retry(Viewer viewer, UUID id) {
        Opened opened = writable(openForChange(viewer, id, SpaceRole.CONTRIBUTOR));
        DocumentRepository.Version version = latest(opened.row());
        if (!"FAILED".equals(version.extractionStatus()) || !PASSING_FAILURES.contains(version.extractionError())) {
            throw ApiException.conflict("not_retryable");
        }
        documents.pending(version.id());
        queue.enqueue(version.id(), opened.space().organizationId(), version.sha256(), version.mediaType());
        return view(viewer, reload(viewer, opened), null);
    }

    AccessView access(Viewer viewer, UUID id) {
        Opened opened = open(viewer, id);
        return accessView(opened);
    }

    /**
     * Contributors move a document between Draft, Published and Obsolete; the maintainers of its
     * space archive it and bring it back. Field errors {@code status:invalid_value},
     * {@code replacedBy:unknown_document} (not found, or not readable by the person: the same answer),
     * {@code replacedBy:not_replaceable} (the document itself, an obsolete or archived one);
     * {@code 403 maintainer_only}.
     */
    @Transactional
    DocumentView changeStatus(Viewer viewer, UUID id, StatusChange change) {
        Opened opened = openForChange(viewer, id, SpaceRole.CONTRIBUTOR);
        // The document, and the one that replaces it, locked and read again: the end of an
        // extraction may change a status without the lock of the space.
        java.util.Set<UUID> locked = new java.util.TreeSet<>();
        locked.add(opened.row().id());
        if (change.replacedBy() != null) {
            locked.add(change.replacedBy());
        }
        Map<UUID, String> statuses = documents.lockStatuses(viewer.organizationId(), locked);
        opened = reload(viewer, opened);
        DocumentRepository.Row row = opened.row();
        Status status = change.status() == null ? null
                : Checks.start().oneOf("status", change.status().strip().toUpperCase(Locale.ROOT), Status.class);
        if (status == null) {
            throw Checks.rejected("status", "invalid_value");
        }
        String after = status.name();
        if ((status == Status.ARCHIVED || row.archived()) && !after.equals(row.status())
                && !access.canArchive(opened.role())) {
            throw ApiException.forbidden("maintainer_only");
        }
        DocumentRepository.Row replacement = null;
        if (status == Status.OBSOLETE && change.replacedBy() != null) {
            if (change.replacedBy().equals(row.id())) {
                throw Checks.rejected("replacedBy", "not_replaceable");
            }
            replacement = documents.findAll(opened.space().organizationId(), List.of(change.replacedBy()), readable(viewer))
                    .stream().findFirst()
                    .orElseThrow(() -> Checks.rejected("replacedBy", "unknown_document"));
            String now = statuses.getOrDefault(replacement.id(), replacement.status());
            if (Status.ARCHIVED.name().equals(now) || Status.OBSOLETE.name().equals(now)) {
                throw Checks.rejected("replacedBy", "not_replaceable");
            }
        }
        UUID replacedBy = replacement == null ? null : replacement.id();
        // A replacement the person cannot read stays: they could neither see it nor mean to remove it.
        if (status == Status.OBSOLETE && replacedBy == null && row.replacedById() != null
                && replacedBy(viewer, row) == null) {
            replacedBy = row.replacedById();
        }
        boolean changed = !after.equals(row.status());
        if (!changed && Objects.equals(replacedBy, row.replacedById())) {
            return view(viewer, opened, null);
        }
        documents.changeStatus(row.id(), after, replacedBy, changed);
        spaces.touch(opened.space().id());
        audit.record(entry(viewer, opened, "document.status_changed")
                .detail("before", row.status())
                .detail("after", after)
                .detail("replacedBy", replacedBy)
                .detail("replacedByTitle", replacement == null ? null : replacement.title())
                .build());
        return view(viewer, reload(viewer, opened), null);
    }

    /** The versions of a document, the latest first. */
    PageResponse<DocumentViews.VersionView> versions(Viewer viewer, UUID id, PageQuery page) {
        Opened opened = open(viewer, id);
        List<DocumentRepository.Version> versions = documents.versions(opened.row().id(), page.offset(), page.size());
        return new PageResponse<>(views.versions(opened.space().organizationId(), versions), page.page(), page.size(),
                opened.row().versionCount());
    }

    /** One version of a document; {@code 404} for a number it does not have. */
    DocumentViews.VersionView version(Viewer viewer, UUID id, int number) {
        Opened opened = open(viewer, id);
        DocumentRepository.Version version = documents.version(opened.row().id(), number).orElseThrow(ApiException::notFound);
        return views.versions(opened.space().organizationId(), List.of(version)).getFirst();
    }

    /** Field error {@code userIds:not_space_member}; {@code 403 maintainer_or_owner_only}. */
    @Transactional
    AccessView changeAccess(Viewer viewer, UUID id, AccessChange change) {
        Opened opened = writable(openForChange(viewer, id, SpaceRole.READER));
        if (!access.canManageAccess(viewer, opened.ref(), opened.role())) {
            throw ApiException.forbidden("maintainer_or_owner_only");
        }
        boolean restricted = change.restricted() == null ? opened.row().restricted() : change.restricted();
        List<UUID> chosen = restricted && change.userIds() != null ? List.copyOf(change.userIds()) : List.of();
        if (restricted && change.userIds() == null) {
            chosen = grants.chosen(opened.row().id());
        }
        DocumentGrants.Change granted = grants.replace(opened.row().id(), opened.space().ref(), chosen, viewer.userId());
        if (restricted != opened.row().restricted()) {
            documents.restrict(opened.row().id(), restricted);
        }
        if (restricted != opened.row().restricted() || !granted.isEmpty()) {
            audit.record(entry(viewer, opened, "document.access_changed")
                    .detail("before", opened.row().restricted() ? "restricted" : "space")
                    .detail("after", restricted ? "restricted" : "space")
                    .detail("added", granted.added())
                    .detail("removed", granted.removed())
                    .build());
        }
        return accessView(reload(viewer, opened));
    }

    /**
     * Hands the document over to another member of the space, at least a contributor: its
     * maintainers, or its owner while a contributor. Field errors {@code userId:required},
     * {@code userId:not_space_member} (a disabled account too), {@code userId:not_contributor};
     * {@code 403 maintainer_or_owner_only}.
     */
    @Transactional
    DocumentView changeOwner(Viewer viewer, UUID id, OwnerChange change) {
        Opened opened = writable(openForChange(viewer, id, SpaceRole.READER));
        if (!access.canManageAccess(viewer, opened.ref(), opened.role())) {
            throw ApiException.forbidden("maintainer_or_owner_only");
        }
        UUID userId = change.userId();
        if (userId == null) {
            throw Checks.rejected("userId", "required");
        }
        SpaceRole role = members.roleOf(opened.space().ref(), userId).orElse(null);
        OrganizationMember person = people.find(opened.space().organizationId(), List.of(userId)).get(userId);
        if (role == null || person == null || !person.active()) {
            throw Checks.rejected("userId", "not_space_member");
        }
        if (!role.includes(SpaceRole.CONTRIBUTOR)) {
            throw Checks.rejected("userId", "not_contributor");
        }
        UUID before = opened.row().ownerId();
        if (userId.equals(before)) {
            return view(viewer, opened, null);
        }
        documents.changeOwner(opened.row().id(), userId);
        spaces.touch(opened.space().id());
        OrganizationMember previous = before == null ? null
                : people.find(opened.space().organizationId(), List.of(before)).get(before);
        audit.record(entry(viewer, opened, "document.owner_changed")
                .detail("from", before)
                .detail("fromName", previous == null ? null : previous.displayName())
                .detail("to", userId)
                .detail("toName", person.displayName())
                .detail("restricted", opened.row().restricted())
                .build());
        Opened after = reload(viewer, opened);
        // Who hands over a restricted document they were not chosen for no longer sees it.
        Optional<SpaceRole> still = access.role(viewer, after.ref());
        if (still.isEmpty()) {
            return views.view(after.space(), after.row(), DocumentActions.NONE, null, null, null);
        }
        return view(viewer, new Opened(after.row(), after.space(), still.get()), null);
    }

    /** The documents the person opened last and still sees, most recent first. Field error {@code size:out_of_range}. */
    List<RecentDocument> recent(Viewer viewer, @Nullable Integer size) {
        int limit = size == null ? RECENT : size;
        if (limit < 1 || limit > MAX_RECENT) {
            Checks.start().fail("size", "out_of_range", Map.of("min", 1, "max", MAX_RECENT)).orThrow();
        }
        Map<UUID, Instant> viewed = new LinkedHashMap<>();
        for (com.dadacore.lore.audit.AuditLog.Recent recent : audit.recent(viewer.organizationId(), viewer.userId(), VIEWED,
                RECENT_VIEWS)) {
            try {
                viewed.putIfAbsent(UUID.fromString(recent.resourceId()), recent.at());
            } catch (IllegalArgumentException e) {
                // Not the identifier of a document: left out.
            }
        }
        if (viewed.isEmpty()) {
            return List.of();
        }
        Map<UUID, DocumentRepository.Row> rows = new HashMap<>();
        for (DocumentRepository.Row row : documents.findAll(viewer.organizationId(), viewed.keySet(), readable(viewer))) {
            rows.put(row.id(), row);
        }
        Map<UUID, DocumentViews.SpaceLink> spaceLinks = new HashMap<>();
        List<RecentDocument> recent = new ArrayList<>();
        for (Map.Entry<UUID, Instant> entry : viewed.entrySet()) {
            DocumentRepository.Row row = rows.get(entry.getKey());
            if (row == null) {
                // Deleted, restricted since, or in a space the person left.
                continue;
            }
            DocumentViews.SpaceLink space = spaceLinks.computeIfAbsent(row.spaceId(), spaceId -> {
                Space found = spaces.find(viewer, spaceId);
                return new DocumentViews.SpaceLink(found.id(), found.name(), found.slug(), found.color());
            });
            recent.add(new RecentDocument(row.id(), row.title(), space, views.folder(row.folderId()), row.status(),
                    entry.getValue()));
            if (recent.size() == limit) {
                break;
            }
        }
        return recent;
    }

    List<AttentionItem> attention(Viewer viewer) {
        List<DocumentRepository.Attention> found = documents.attention(viewer.organizationId(), viewer.userId(),
                readable(viewer), ATTENTION);
        Map<UUID, DocumentViews.SpaceLink> links = new LinkedHashMap<>();
        List<AttentionItem> items = new ArrayList<>();
        for (DocumentRepository.Attention item : found) {
            DocumentRepository.Row row = item.document();
            DocumentViews.SpaceLink space = links.computeIfAbsent(row.spaceId(), spaceId -> {
                Space shown = spaces.find(viewer, spaceId);
                return new DocumentViews.SpaceLink(shown.id(), shown.name(), shown.slug(), shown.color());
            });
            items.add(new AttentionItem(row.id(), row.title(), space, item.version().number(),
                    item.version().extractionStatus(), item.version().extractionError()));
        }
        return items;
    }

    // Shared with the import.

    SqlCondition readable(Viewer viewer) {
        return access.readableDocuments(viewer, "d", "s");
    }

    DocumentView view(Viewer viewer, Opened opened, DocumentViews.@Nullable DocumentLink duplicateOf) {
        DocumentRepository.Row row = opened.row();
        boolean active = !opened.space().archived();
        boolean writable = active && !row.archived();
        boolean contributor = opened.role().includes(SpaceRole.CONTRIBUTOR);
        DocumentRepository.Version newer = row.latestNumber() > row.version().number() ? documents.latest(row.id()) : null;
        DocumentRepository.Version latest = newer == null ? row.version() : newer;
        boolean manageAccess = writable && access.canManageAccess(viewer, opened.ref(), opened.role());
        boolean archive = active && access.canArchive(opened.role());
        DocumentActions actions = new DocumentActions(
                writable && contributor,
                active && access.canDelete(viewer, opened.ref(), opened.role()),
                manageAccess,
                writable && contributor && "FAILED".equals(latest.extractionStatus())
                        && PASSING_FAILURES.contains(latest.extractionError()),
                manageAccess,
                writable && contributor,
                active && contributor && (!row.archived() || archive),
                archive);
        return views.view(opened.space(), row, actions, duplicateOf, replacedBy(viewer, row), newer);
    }

    /** The document that replaces an obsolete one, when the person may read it: nothing otherwise, not even its title. */
    DocumentViews.@Nullable DocumentLink replacedBy(Viewer viewer, DocumentRepository.Row row) {
        if (row.replacedById() == null) {
            return null;
        }
        return documents.findAll(viewer.organizationId(), List.of(row.replacedById()), readable(viewer)).stream()
                .findFirst()
                .map(found -> new DocumentViews.DocumentLink(found.id(), found.title()))
                .orElse(null);
    }

    /** A document, its space and the role of the person, once their right to see it is checked. */
    record Opened(DocumentRepository.Row row, Space space, SpaceRole role) {

        DocumentRef ref() {
            return new DocumentRef(row.id(), space.ref(), row.ownerId(), row.createdBy(), row.restricted(),
                    row.versionCount(), row.archived());
        }
    }

    Opened open(Viewer viewer, UUID id) {
        DocumentRepository.Row row = documents.find(viewer.organizationId(), id).orElseThrow(ApiException::notFound);
        Space space = spaces.find(viewer, row.spaceId());
        Opened unchecked = new Opened(row, space, SpaceRole.READER);
        SpaceRole role = access.role(viewer, unchecked.ref()).orElseThrow(ApiException::notFound);
        return new Opened(row, space, role);
    }

    /** The latest version of a document: the current one, or a newer one being extracted or failed. */
    DocumentRepository.Version latest(DocumentRepository.Row row) {
        return row.latestNumber() > row.version().number() ? documents.latest(row.id()) : row.version();
    }

    /** An archived document is read only: {@code 409 document_archived}, but for its status and its deletion. */
    static Opened writable(Opened opened) {
        if (opened.row().archived()) {
            throw ApiException.conflict("document_archived");
        }
        return opened;
    }

    /** As {@link #open}, the space locked until the end of the transaction, and active. */
    Opened openForChange(Viewer viewer, UUID id, SpaceRole minimum) {
        DocumentRepository.Row row = documents.find(viewer.organizationId(), id).orElseThrow(ApiException::notFound);
        Space space = spaces.openForChange(viewer, row.spaceId(), SpaceRole.READER).space();
        // Read again under the lock of the space, which a deletion or a move would have waited for.
        row = documents.find(viewer.organizationId(), id).orElseThrow(ApiException::notFound);
        Opened unchecked = new Opened(row, space, SpaceRole.READER);
        SpaceRole role = access.require(viewer, unchecked.ref(), minimum);
        space.ref().requireActive();
        return new Opened(row, space, role);
    }

    Opened reload(Viewer viewer, Opened opened) {
        DocumentRepository.Row row = documents.find(viewer.organizationId(), opened.row().id()).orElseThrow(ApiException::notFound);
        return new Opened(row, opened.space(), opened.role());
    }

    private AccessView accessView(Opened opened) {
        Map<UUID, List<String>> reasons = new LinkedHashMap<>();
        if (opened.row().restricted()) {
            grants.chosen(opened.row().id()).forEach(id -> reason(reasons, id, "chosen"));
            grants.maintainers(opened.space().ref()).forEach(id -> reason(reasons, id, "maintainer"));
            if (opened.row().ownerId() != null) {
                reason(reasons, opened.row().ownerId(), "owner");
            }
        }
        Map<UUID, OrganizationMember> found = reasons.isEmpty() ? Map.of()
                : people.find(opened.space().organizationId(), reasons.keySet());
        List<AccessPerson> list = reasons.entrySet().stream()
                .filter(entry -> found.containsKey(entry.getKey()))
                .map(entry -> {
                    OrganizationMember person = found.get(entry.getKey());
                    return new AccessPerson(person.id(), person.displayName(), person.email(), entry.getValue());
                })
                .toList();
        int spaceMembers = members.counts(List.of(opened.space().id())).getOrDefault(opened.space().id(), 0);
        return new AccessView(opened.row().restricted(), spaceMembers, list);
    }

    private static void reason(Map<UUID, List<String>> reasons, UUID id, String reason) {
        reasons.computeIfAbsent(id, key -> new ArrayList<>()).add(reason);
    }

    private static @Nullable String name(@Nullable Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static AuditEntry.Builder entry(Viewer viewer, Opened opened, String action) {
        return AuditEntry.of(opened.space().organizationId(), viewer.userId(), action)
                .resource("document", opened.row().id())
                .ip(viewer.ip())
                .detail("title", opened.row().title())
                .detail("space", opened.space().name())
                .detail("spaceId", opened.space().id());
    }
}
