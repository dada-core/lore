package com.dadacore.lore.library.spaces;

import java.util.List;
import java.util.UUID;

import com.dadacore.lore.identity.Viewer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
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
 * A space the person cannot see answers {@code 404}; an action their role does not allow,
 * {@code 403 maintainer_only}.
 */
@RestController
@RequestMapping("/api/v1/spaces")
@Tag(name = "Spaces")
class SpacesController {

    private final Spaces spaces;

    SpacesController(Spaces spaces) {
        this.spaces = spaces;
    }

    @GetMapping
    @Operation(summary = "The spaces the person can see, by name; archived=true lists the archived ones")
    List<Spaces.SpaceView> list(Viewer viewer, @RequestParam(defaultValue = "false") boolean archived) {
        return spaces.list(viewer, archived);
    }

    /** Field errors {@code slug_taken} and {@code invalid_slug}. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a space: its creator becomes its maintainer")
    Spaces.SpaceView create(Viewer viewer, @RequestBody Spaces.NewSpace body) {
        return spaces.create(viewer, body);
    }

    @GetMapping("/by-slug/{slug}")
    @Operation(summary = "A space, by its address in the interface")
    Spaces.SpaceView bySlug(Viewer viewer, @PathVariable String slug) {
        return spaces.bySlug(viewer, slug);
    }

    @GetMapping("/{id}")
    @Operation(summary = "A space")
    Spaces.SpaceView get(Viewer viewer, @PathVariable UUID id) {
        return spaces.get(viewer, id);
    }

    /** {@code 409 space_archived}; field errors {@code slug_taken} and {@code invalid_slug}. */
    @PatchMapping("/{id}")
    @Operation(summary = "Change the name, description, address or color of a space")
    Spaces.SpaceView update(Viewer viewer, @PathVariable UUID id, @RequestBody Spaces.SpaceChanges body) {
        return spaces.update(viewer, id, body);
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "Archive a space: only its maintainers still see it, and it can no longer be changed")
    Spaces.SpaceView archive(Viewer viewer, @PathVariable UUID id) {
        return spaces.archive(viewer, id, true);
    }

    @PostMapping("/{id}/unarchive")
    @Operation(summary = "Make an archived space active again")
    Spaces.SpaceView unarchive(Viewer viewer, @PathVariable UUID id) {
        return spaces.archive(viewer, id, false);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a space, with its folders and its members")
    void delete(Viewer viewer, @PathVariable UUID id) {
        spaces.delete(viewer, id);
    }
}
