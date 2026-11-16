package com.dadacore.lore.library.spaces;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.dadacore.lore.access.SpaceAccess;
import com.dadacore.lore.access.SpaceMembers;
import com.dadacore.lore.access.SpaceRef;
import com.dadacore.lore.access.SpaceRole;
import com.dadacore.lore.audit.AuditEntry;
import com.dadacore.lore.audit.AuditLog;
import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.shared.ApiException;
import com.dadacore.lore.shared.Checks;
import com.dadacore.lore.shared.Slugs;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The spaces of an organization.
 *
 * <p>Every member of the organization can create a space, and becomes its maintainer. The
 * maintainers change its settings, archive it (only maintainers still see it, and it can no
 * longer be changed until it is made active again) and delete it.
 */
@Service
public class Spaces {

    /** The colors offered for a space. */
    static final List<String> COLORS = List.of(
            "#13705E", "#3F6C9A", "#8A6A1F", "#6B5A8E", "#8E4B44", "#4E7A5A", "#5B5FD6");
    private static final int MAX_NAME_LENGTH = 80;
    private static final int MAX_DESCRIPTION_LENGTH = 500;

    private final SpaceRepository repository;
    private final SpaceAccess access;
    private final SpaceMembers members;
    private final AuditLog audit;

    Spaces(SpaceRepository repository, SpaceAccess access, SpaceMembers members, AuditLog audit) {
        this.repository = repository;
        this.access = access;
        this.members = members;
        this.audit = audit;
    }

    /**
     * @param slug           the address of the space in the interface: /spaces/{slug}
     * @param role           the role of the person: maintainer for owners and administrators
     * @param memberCount    the members of the space, without the administrators who are not
     * @param lastActivityAt the last change of the space or of its folders
     * @param archivedAt     null while the space is active
     */
    record SpaceView(UUID id, String name, String slug, @Nullable String description, String color, String role,
            int memberCount, Instant createdAt, Instant lastActivityAt, @Nullable Instant archivedAt) {
    }

    /**
     * @param slug  optional: derived from the name when missing
     * @param color optional: one of the colors of the interface
     */
    record NewSpace(String name, @Nullable String description, @Nullable String slug, @Nullable String color) {
    }

    /** Each missing field stays as it is; an empty description removes it. */
    record SpaceChanges(@Nullable String name, @Nullable String description, @Nullable String slug,
            @Nullable String color) {
    }

    /** A space and the role of the person who opened it. */
    public record OpenedSpace(Space space, SpaceRole role) {

        public SpaceRef ref() {
            return space.ref();
        }

        /** An archived space can no longer be changed: {@code 409 space_archived}. */
        public OpenedSpace requireActive() {
            ref().requireActive();
            return this;
        }
    }

    /**
     * The space, with the role of the person when it includes {@code minimum}: {@code 404} when
     * they cannot see it, {@code 403} when their role is not enough.
     */
    public OpenedSpace open(Viewer viewer, UUID id, SpaceRole minimum) {
        Space space = find(viewer, id);
        return new OpenedSpace(space, access.require(viewer, space.ref(), minimum));
    }

    /**
     * As {@link #open}, for a change to the space or to its content. The space stays locked until
     * the end of the transaction, so that it is neither archived nor deleted in the meantime.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OpenedSpace openForChange(Viewer viewer, UUID id, SpaceRole minimum) {
        Space space = repository.lock(viewer.organizationId(), id).orElseThrow(ApiException::notFound);
        return new OpenedSpace(space, access.require(viewer, space.ref(), minimum));
    }

    /** The space within the organization of the person, before any check of their role. */
    public Space find(Viewer viewer, UUID id) {
        return repository.find(viewer.organizationId(), id).orElseThrow(ApiException::notFound);
    }

    /** Records a change inside the space, shown as its last activity. */
    public void touch(UUID spaceId) {
        repository.touch(spaceId);
    }

    List<SpaceView> list(Viewer viewer, boolean archived) {
        List<Space> spaces = repository.list(viewer.organizationId(), archived,
                access.visibleSpaces(viewer, "s.id", "s.archived_at"));
        List<UUID> ids = spaces.stream().map(Space::id).toList();
        Map<UUID, SpaceRole> roles = access.roles(viewer, ids);
        Map<UUID, Integer> counts = members.counts(ids);
        return spaces.stream()
                .filter(space -> roles.containsKey(space.id()))
                .map(space -> view(space, roles.get(space.id()), counts.getOrDefault(space.id(), 0)))
                .toList();
    }

    SpaceView get(Viewer viewer, UUID id) {
        return view(open(viewer, id, SpaceRole.READER));
    }

    SpaceView bySlug(Viewer viewer, String slug) {
        Space space = repository.findBySlug(viewer.organizationId(), slug).orElseThrow(ApiException::notFound);
        return view(new OpenedSpace(space, access.require(viewer, space.ref(), SpaceRole.READER)));
    }

    /** Field errors {@code slug_taken} and {@code invalid_slug}. */
    @Transactional
    SpaceView create(Viewer viewer, NewSpace request) {
        Checks checks = Checks.start();
        String name = checks.text("name", request.name(), MAX_NAME_LENGTH);
        String description = description(checks, request.description());
        boolean slugChosen = request.slug() != null && !request.slug().isBlank();
        String slug = slugChosen ? slug(checks, request.slug()) : null;
        boolean colorChosen = request.color() != null && !request.color().isBlank();
        String color = colorChosen ? color(checks, request.color()) : null;
        checks.orThrow();
        UUID organizationId = viewer.organizationId();
        if (!colorChosen) {
            // The next colour after the spaces the person sees, as the interface suggests it. All the
            // spaces of the organization would tell how many are hidden.
            int seen = repository.countActive(organizationId, access.visibleSpaces(viewer, "s.id", "s.archived_at"));
            color = COLORS.get(seen % COLORS.size());
        }
        Space space;
        if (slugChosen) {
            space = repository.insert(organizationId, name, slug, description, color, viewer.userId())
                    .orElseThrow(Spaces::slugTaken);
        } else {
            // Someone may take the free address in the meantime: the next one is tried then.
            Optional<Space> created = Optional.empty();
            for (int attempt = 0; created.isEmpty() && attempt < 10; attempt++) {
                slug = available(organizationId, Slugs.of(name, "space"));
                created = repository.insert(organizationId, name, slug, description, color, viewer.userId());
            }
            space = created.orElseThrow(Spaces::slugTaken);
        }
        members.addCreator(space.ref(), viewer.userId());
        audit.record(AuditEntry.of(organizationId, viewer.userId(), "space.created")
                .resource("space", space.id())
                .ip(viewer.ip())
                .detail("name", name)
                .detail("slug", slug)
                .build());
        return view(space, SpaceRole.MAINTAINER, 1);
    }

    /** {@code 403 maintainer_only}, {@code 409 space_archived}; field errors as for a new space. */
    @Transactional
    SpaceView update(Viewer viewer, UUID id, SpaceChanges changes) {
        OpenedSpace opened = openForChange(viewer, id, SpaceRole.MAINTAINER).requireActive();
        Space space = opened.space();
        Checks checks = Checks.start();
        String name = changes.name() == null ? space.name() : checks.text("name", changes.name(), MAX_NAME_LENGTH);
        String description = changes.description() == null
                ? space.description()
                : description(checks, changes.description());
        String slug = changes.slug() == null ? space.slug() : slug(checks, changes.slug());
        String color = changes.color() == null ? space.color() : color(checks, changes.color());
        checks.orThrow();
        if (!slug.equals(space.slug()) && repository.slugTaken(space.organizationId(), slug, space.id())) {
            throw slugTaken();
        }
        List<String> changed = new ArrayList<>();
        if (!name.equals(space.name())) {
            changed.add("name");
        }
        if (!Objects.equals(description, space.description())) {
            changed.add("description");
        }
        if (!slug.equals(space.slug())) {
            changed.add("slug");
        }
        if (!color.equals(space.color())) {
            changed.add("color");
        }
        if (changed.isEmpty()) {
            return view(opened);
        }
        try {
            repository.update(space.id(), name, slug, description, color);
        } catch (DuplicateKeyException e) {
            throw slugTaken();
        }
        audit.record(AuditEntry.of(space.organizationId(), viewer.userId(), "space.updated")
                .resource("space", space.id())
                .ip(viewer.ip())
                .detail("name", name)
                .detail("changes", changed)
                .detail("previousName", changed.contains("name") ? space.name() : null)
                .detail("previousSlug", changed.contains("slug") ? space.slug() : null)
                .build());
        return view(open(viewer, id, SpaceRole.MAINTAINER));
    }

    /** {@code 403 maintainer_only}. Archiving an archived space, or the reverse, changes nothing. */
    @Transactional
    SpaceView archive(Viewer viewer, UUID id, boolean archived) {
        OpenedSpace opened = openForChange(viewer, id, SpaceRole.MAINTAINER);
        Space space = opened.space();
        if (space.archived() == archived) {
            return view(opened);
        }
        repository.archive(space.id(), archived);
        audit.record(AuditEntry.of(space.organizationId(), viewer.userId(),
                        archived ? "space.archived" : "space.unarchived")
                .resource("space", space.id())
                .ip(viewer.ip())
                .detail("name", space.name())
                .build());
        return view(open(viewer, id, SpaceRole.MAINTAINER));
    }

    /** Deletes the space, its folders and its members. {@code 403 maintainer_only}. */
    @Transactional
    void delete(Viewer viewer, UUID id) {
        Space space = openForChange(viewer, id, SpaceRole.MAINTAINER).space();
        repository.delete(space.id());
        audit.record(AuditEntry.of(space.organizationId(), viewer.userId(), "space.deleted")
                .resource("space", space.id())
                .ip(viewer.ip())
                .detail("name", space.name())
                .detail("slug", space.slug())
                .build());
    }

    /** The address derived from the name, followed by -2, -3... when it is already used. */
    private String available(UUID organizationId, String base) {
        String slug = base;
        for (int n = 2; repository.slugTaken(organizationId, slug, null); n++) {
            String suffix = "-" + n;
            String start = base.length() + suffix.length() > Slugs.MAX_LENGTH
                    ? base.substring(0, Slugs.MAX_LENGTH - suffix.length()).replaceAll("-+$", "")
                    : base;
            slug = start + suffix;
        }
        return slug;
    }

    private static @Nullable String description(Checks checks, @Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String description = value.strip();
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            checks.fail("description", "too_long", Map.of("max", MAX_DESCRIPTION_LENGTH));
            return null;
        }
        return description;
    }

    private static @Nullable String slug(Checks checks, String value) {
        String slug = value.strip().toLowerCase(Locale.ROOT);
        if (!Slugs.isValid(slug)) {
            checks.fail("slug", "invalid_slug");
            return null;
        }
        return slug;
    }

    private static @Nullable String color(Checks checks, String value) {
        String color = value.strip().toUpperCase(Locale.ROOT);
        if (!COLORS.contains(color)) {
            checks.fail("color", "invalid_value");
            return null;
        }
        return color;
    }

    /**
     * An address is unique in the organization, so this answer also tells that a space the person
     * cannot see uses it. Names and addresses of spaces are therefore not secret within the
     * organization, unlike their content (ADR 0004).
     */
    private static ApiException slugTaken() {
        return Checks.rejected("slug", "slug_taken");
    }

    private SpaceView view(OpenedSpace opened) {
        Space space = opened.space();
        return view(space, opened.role(), members.counts(List.of(space.id())).getOrDefault(space.id(), 0));
    }

    private static SpaceView view(Space space, SpaceRole role, int memberCount) {
        return new SpaceView(space.id(), space.name(), space.slug(), space.description(), space.color(),
                role.name(), memberCount, space.createdAt(), space.updatedAt(), space.archivedAt());
    }
}
