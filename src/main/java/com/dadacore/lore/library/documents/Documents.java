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
 * description and category, its folder, its access, its deletion, and a new attempt at its
 * extraction.
 *
 * <p>Every read adds the condition of {@link DocumentAccess}: a document the person cannot see
 * appears nowhere, not even in a count. Every change locks the space first, like the folders, and
 * an archived space refuses it ({@code 409 space_archived}).
 */
@Service
public class Documents {

    static final int MAX_DESCRIPTION = 2000;
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

    /** The statuses a document can be imported with; the others come with the versions. */
    enum ImportStatus {
        DRAFT, PUBLISHED
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
    private final AuditLog audit;

    Documents(DocumentRepository documents, DocumentViews views, Spaces spaces, DocumentAccess access,
            DocumentGrants grants, SpaceMembers members, OrganizationMembers people, ExtractionQueue queue,
            FileJanitor janitor, AuditLog audit) {
        this.documents = documents;
        this.views = views;
        this.spaces = spaces;
        this.access = access;
        this.grants = grants;
        this.members = members;
        this.people = people;
        this.queue = queue;
        this.janitor = janitor;
        this.audit = audit;
    }

    /** The changes of a document; a field left out keeps its value, an empty one is removed. */
    record DocumentChanges(@Nullable String title, @Nullable String description, @Nullable String category) {
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

    /** The counts of the documents the person sees in a space. */
    record DocumentSummary(long total, long inProgress, long scanned, long failed, List<FolderDocuments> folders) {
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

    /** A document imported by the person, still in progress or failed, for the home page. */
    record AttentionItem(UUID id, String title, DocumentViews.SpaceLink space, String extractionStatus,
            @Nullable String extractionError) {
    }

    PageResponse<DocumentItem> list(Viewer viewer, UUID spaceId, @Nullable String folder, @Nullable Boolean restricted,
            @Nullable List<UUID> ids, @Nullable String sort, @Nullable String direction, PageQuery page) {
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
        DocumentRepository.Filter filter = new DocumentRepository.Filter(folderId, root, restricted, chosen);
        SqlCondition readable = readable(viewer);
        List<DocumentRepository.Row> rows = documents.list(space.id(), readable, filter, order, desc, page.offset(),
                page.size());
        return new PageResponse<>(views.items(space.organizationId(), rows), page.page(), page.size(),
                documents.count(space.id(), readable, filter));
    }

    DocumentSummary summary(Viewer viewer, UUID spaceId) {
        Space space = spaces.open(viewer, spaceId, SpaceRole.READER).space();
        DocumentRepository.Summary summary = documents.summary(space.id(), readable(viewer));
        return new DocumentSummary(summary.total(), summary.inProgress(), summary.scanned(), summary.failed(),
                summary.folders().stream().map(f -> new FolderDocuments(f.folderId(), f.count())).toList());
    }

    /** The page of a document: its opening is a view, recorded once an hour at most. */
    DocumentView get(Viewer viewer, UUID id) {
        Opened opened = open(viewer, id);
        audit.recordOnce(entry(viewer, opened, VIEWED).detail("version", opened.row().version().number()).build(),
                VIEW_ONCE);
        return view(viewer, opened, null);
    }

    /** Field errors {@code title:required}, {@code title:too_long}, {@code description:too_long}, {@code category:invalid_value}. */
    @Transactional
    DocumentView update(Viewer viewer, UUID id, DocumentChanges changes) {
        Opened opened = openForChange(viewer, id, SpaceRole.CONTRIBUTOR);
        DocumentRepository.Row row = opened.row();
        Checks checks = Checks.start();
        String title = changes.title() == null ? row.title() : checks.text("title", changes.title(), Titles.MAX_TITLE);
        String description = row.description();
        if (changes.description() != null) {
            description = changes.description().strip().isEmpty() ? null : changes.description().strip();
            if (description != null && description.length() > MAX_DESCRIPTION) {
                checks.fail("description", "too_long", Map.of("max", MAX_DESCRIPTION));
            }
        }
        String category = row.category();
        if (changes.category() != null) {
            category = changes.category().isBlank() ? null : name(checks.oneOf("category", changes.category(), Category.class));
        }
        checks.orThrow();
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
        if (!changed.isEmpty()) {
            documents.update(row.id(), title, description, category);
            spaces.touch(opened.space().id());
            audit.record(entry(viewer, opened, "document.updated")
                    .detail("changes", changed)
                    .detail("previousTitle", changed.contains("title") ? row.title() : null)
                    .build());
        }
        return view(viewer, reload(viewer, opened), null);
    }

    /** Field error {@code folderId:unknown_folder}, the same for a folder of another space. */
    @Transactional
    DocumentView move(Viewer viewer, UUID id, DocumentMove move) {
        Opened opened = openForChange(viewer, id, SpaceRole.CONTRIBUTOR);
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
        documents.delete(opened.row().id());
        spaces.touch(opened.space().id());
        audit.record(entry(viewer, opened, "document.deleted")
                .detail("versions", opened.row().versionCount())
                .detail("filename", opened.row().version().filename())
                .build());
        janitor.afterCommit(opened.space().organizationId(), contents);
    }

    /** {@code 409 not_retryable} for a final failure, or a version that did not fail. */
    @Transactional
    DocumentView retry(Viewer viewer, UUID id) {
        Opened opened = openForChange(viewer, id, SpaceRole.CONTRIBUTOR);
        DocumentRepository.Version version = opened.row().version();
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

    /** Field error {@code userIds:not_space_member}; {@code 403 maintainer_or_owner_only}. */
    @Transactional
    AccessView changeAccess(Viewer viewer, UUID id, AccessChange change) {
        Opened opened = openForChange(viewer, id, SpaceRole.READER);
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
        Opened opened = openForChange(viewer, id, SpaceRole.READER);
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
            return views.view(after.space(), after.row(), DocumentActions.NONE, null);
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
        List<DocumentRepository.Row> rows = documents.attention(viewer.organizationId(), viewer.userId(), readable(viewer),
                ATTENTION);
        Map<UUID, DocumentViews.SpaceLink> links = new LinkedHashMap<>();
        List<AttentionItem> items = new ArrayList<>();
        for (DocumentRepository.Row row : rows) {
            DocumentViews.SpaceLink space = links.computeIfAbsent(row.spaceId(), spaceId -> {
                Space found = spaces.find(viewer, spaceId);
                return new DocumentViews.SpaceLink(found.id(), found.name(), found.slug(), found.color());
            });
            items.add(new AttentionItem(row.id(), row.title(), space, row.version().extractionStatus(),
                    row.version().extractionError()));
        }
        return items;
    }

    // Shared with the import.

    SqlCondition readable(Viewer viewer) {
        return access.readableDocuments(viewer, "d", "s");
    }

    DocumentView view(Viewer viewer, Opened opened, DocumentViews.@Nullable DocumentLink duplicateOf) {
        boolean active = !opened.space().archived();
        boolean contributor = opened.role().includes(SpaceRole.CONTRIBUTOR);
        DocumentRepository.Version version = opened.row().version();
        boolean manageAccess = active && access.canManageAccess(viewer, opened.ref(), opened.role());
        DocumentActions actions = new DocumentActions(
                active && contributor,
                active && access.canDelete(viewer, opened.ref(), opened.role()),
                manageAccess,
                active && contributor && "FAILED".equals(version.extractionStatus())
                        && PASSING_FAILURES.contains(version.extractionError()),
                manageAccess);
        return views.view(opened.space(), opened.row(), actions, duplicateOf);
    }

    /** A document, its space and the role of the person, once their right to see it is checked. */
    record Opened(DocumentRepository.Row row, Space space, SpaceRole role) {

        DocumentRef ref() {
            return new DocumentRef(row.id(), space.ref(), row.ownerId(), row.createdBy(), row.restricted(),
                    row.versionCount());
        }
    }

    Opened open(Viewer viewer, UUID id) {
        DocumentRepository.Row row = documents.find(viewer.organizationId(), id).orElseThrow(ApiException::notFound);
        Space space = spaces.find(viewer, row.spaceId());
        Opened unchecked = new Opened(row, space, SpaceRole.READER);
        SpaceRole role = access.role(viewer, unchecked.ref()).orElseThrow(ApiException::notFound);
        return new Opened(row, space, role);
    }

    /** As {@link #open}, the space locked until the end of the transaction, and active. */
    private Opened openForChange(Viewer viewer, UUID id, SpaceRole minimum) {
        DocumentRepository.Row row = documents.find(viewer.organizationId(), id).orElseThrow(ApiException::notFound);
        Space space = spaces.openForChange(viewer, row.spaceId(), SpaceRole.READER).space();
        // Read again under the lock of the space, which a deletion or a move would have waited for.
        row = documents.find(viewer.organizationId(), id).orElseThrow(ApiException::notFound);
        Opened unchecked = new Opened(row, space, SpaceRole.READER);
        SpaceRole role = access.require(viewer, unchecked.ref(), minimum);
        space.ref().requireActive();
        return new Opened(row, space, role);
    }

    private Opened reload(Viewer viewer, Opened opened) {
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
