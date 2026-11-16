package com.dadacore.lore.library.spaces;

import java.util.List;
import java.util.UUID;

import com.dadacore.lore.access.SpaceMembers;
import com.dadacore.lore.access.SpaceRef;
import com.dadacore.lore.access.SpaceRole;
import com.dadacore.lore.identity.Viewer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The members of a space: everyone who sees the space sees them; its maintainers manage them
 * ({@code 403 maintainer_only} otherwise). The rules are those of the access module.
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/members")
@Tag(name = "Spaces")
class SpaceMembersController {

    private final Spaces spaces;
    private final SpaceMembers members;

    SpaceMembersController(Spaces spaces, SpaceMembers members) {
        this.spaces = spaces;
        this.members = members;
    }

    /** @param role {@code READER}, {@code CONTRIBUTOR} or {@code MAINTAINER} */
    record NewMember(UUID userId, String role) {
    }

    record SpaceRoleChange(String role) {
    }

    @GetMapping
    @Operation(summary = "Members of a space, by name")
    List<SpaceMembers.SpaceMemberView> list(Viewer viewer, @PathVariable UUID spaceId) {
        return members.list(viewer, spaces.find(viewer, spaceId).ref());
    }

    @GetMapping("/candidates")
    @Operation(summary = "Members of the organization who can be added to the space; q searches names and addresses")
    List<SpaceMembers.Candidate> candidates(Viewer viewer, @PathVariable UUID spaceId,
            @RequestParam(required = false) String q) {
        return members.candidates(viewer, spaces.find(viewer, spaceId).ref(), q);
    }

    /** Field error {@code userId:unknown_member}; {@code 409 already_space_member}, {@code 409 space_archived}. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a member of the organization to the space")
    @Transactional
    SpaceMembers.SpaceMemberView add(Viewer viewer, @PathVariable UUID spaceId, @RequestBody NewMember body) {
        return members.add(viewer, changing(viewer, spaceId), body.userId(), body.role());
    }

    /** {@code 409 last_maintainer}: a space always keeps a maintainer. */
    @PatchMapping("/{userId}")
    @Operation(summary = "Change the role of a member in the space")
    @Transactional
    SpaceMembers.SpaceMemberView changeRole(Viewer viewer, @PathVariable UUID spaceId, @PathVariable UUID userId,
            @RequestBody SpaceRoleChange body) {
        return members.changeRole(viewer, changing(viewer, spaceId), userId, body.role());
    }

    /** Anyone can leave a space. {@code 409 last_maintainer}. */
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a member from the space, or leave it")
    @Transactional
    void remove(Viewer viewer, @PathVariable UUID spaceId, @PathVariable UUID userId) {
        members.remove(viewer, changing(viewer, spaceId), userId);
    }

    /**
     * The space, locked until the change is recorded: it cannot be archived or deleted in the
     * meantime. The role needed is checked by {@link SpaceMembers}.
     */
    private SpaceRef changing(Viewer viewer, UUID spaceId) {
        return spaces.openForChange(viewer, spaceId, SpaceRole.READER).ref();
    }
}
