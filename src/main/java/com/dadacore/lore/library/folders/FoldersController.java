package com.dadacore.lore.library.folders;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Everyone who sees the space sees its folders; contributors and maintainers change them
 * ({@code 403 contributor_only} otherwise).
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}/folders")
@Tag(name = "Spaces")
class FoldersController {

    private final Folders folders;

    FoldersController(Folders folders) {
        this.folders = folders;
    }

    record FolderName(String name) {
    }

    @GetMapping
    @Operation(summary = "Folders of a space, in the order of their creation")
    List<Folders.FolderView> list(Viewer viewer, @PathVariable UUID spaceId) {
        return folders.list(viewer, spaceId);
    }

    /** Field error {@code name:name_taken}; {@code 409 space_archived}. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a folder")
    Folders.FolderView create(Viewer viewer, @PathVariable UUID spaceId, @RequestBody FolderName body) {
        return folders.create(viewer, spaceId, body.name());
    }

    /** Field error {@code name:name_taken}. */
    @PatchMapping("/{folderId}")
    @Operation(summary = "Rename a folder")
    Folders.FolderView rename(Viewer viewer, @PathVariable UUID spaceId, @PathVariable UUID folderId,
            @RequestBody FolderName body) {
        return folders.rename(viewer, spaceId, folderId, body.name());
    }

    @DeleteMapping("/{folderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a folder")
    void delete(Viewer viewer, @PathVariable UUID spaceId, @PathVariable UUID folderId) {
        folders.delete(viewer, spaceId, folderId);
    }
}
