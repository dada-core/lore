package com.dadacore.lore.library.documents;

import java.util.List;
import java.util.UUID;

import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.library.documents.DocumentViews.DocumentItem;
import com.dadacore.lore.library.documents.DocumentViews.DocumentView;
import com.dadacore.lore.shared.PageQuery;
import com.dadacore.lore.shared.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The documents of the spaces. A document the person cannot see answers {@code 404}; an action
 * their role does not allow, {@code 403}; an archived space refuses any change,
 * {@code 409 space_archived}.
 */
@RestController
@Tag(name = "Documents")
class DocumentsController {

    private final Documents documents;
    private final Imports imports;
    private final StorageSettings storage;

    DocumentsController(Documents documents, Imports imports, StorageSettings storage) {
        this.documents = documents;
        this.imports = imports;
        this.storage = storage;
    }

    /**
     * @param file       the file: PDF, Word (.docx), Markdown or text
     * @param folderId   its folder in the space; none for the root
     * @param status     {@code DRAFT} (default) or {@code PUBLISHED}
     * @param category   one of the categories, or none
     * @param restricted true to show it only to the people chosen, the maintainers and its owner
     * @param userIds    the people chosen, members of the space
     */
    record ImportForm(@Schema(type = "string", format = "binary") String file, @Nullable UUID folderId,
            @Nullable String status, @Nullable String category, @Nullable Boolean restricted, @Nullable List<UUID> userIds) {
    }

    @GetMapping("/api/v1/spaces/{spaceId}/documents")
    @Operation(summary = "A page of the documents of a space the person can see, by folder then most recent first; "
            + "folder=none lists those without a folder, ids (100 at most) follows given documents")
    PageResponse<DocumentItem> list(Viewer viewer, @PathVariable UUID spaceId,
            @RequestParam(required = false) @Nullable String folder,
            @RequestParam(required = false) @Nullable Boolean restricted,
            @RequestParam(required = false) @Nullable List<UUID> ids,
            @RequestParam(required = false) @Nullable Integer page,
            @RequestParam(required = false) @Nullable Integer size) {
        return documents.list(viewer, spaceId, folder, restricted, ids, PageQuery.of(page, size));
    }

    @GetMapping("/api/v1/spaces/{spaceId}/documents/summary")
    @Operation(summary = "The counts of the documents of a space the person can see: in all, by folder, in progress, scanned, failed")
    Documents.DocumentSummary summary(Viewer viewer, @PathVariable UUID spaceId) {
        return documents.summary(viewer, spaceId);
    }

    @PostMapping(path = "/api/v1/spaces/{spaceId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Import a file into a space: a new document and its version 1, whose text is extracted next; "
            + "duplicateOf names a document of the space with the same content",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(implementation = ImportForm.class))))
    DocumentView importFile(Viewer viewer, @PathVariable UUID spaceId, HttpServletRequest request) {
        return imports.importFile(viewer, spaceId, request);
    }

    @GetMapping("/api/v1/documents/{id}")
    @Operation(summary = "A document, its current version and what the person may do with it")
    DocumentView get(Viewer viewer, @PathVariable UUID id) {
        return documents.get(viewer, id);
    }

    @PatchMapping("/api/v1/documents/{id}")
    @Operation(summary = "Change the title, the description or the category; an empty description or category is removed")
    DocumentView update(Viewer viewer, @PathVariable UUID id, @RequestBody Documents.DocumentChanges body) {
        return documents.update(viewer, id, body);
    }

    @PostMapping("/api/v1/documents/{id}/move")
    @Operation(summary = "Move a document to another folder of its space, or to its root with folderId null")
    DocumentView move(Viewer viewer, @PathVariable UUID id, @RequestBody Documents.DocumentMove body) {
        return documents.move(viewer, id, body);
    }

    @DeleteMapping("/api/v1/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a document for good: its maintainers, or who imported it while it has one version")
    void delete(Viewer viewer, @PathVariable UUID id) {
        documents.delete(viewer, id);
    }

    @PostMapping("/api/v1/documents/{id}/extraction/retry")
    @Operation(summary = "Try the extraction again after a passing failure; 409 not_retryable otherwise")
    DocumentView retry(Viewer viewer, @PathVariable UUID id) {
        return documents.retry(viewer, id);
    }

    @GetMapping("/api/v1/documents/{id}/access")
    @Operation(summary = "Who sees a restricted document, and why")
    Documents.AccessView access(Viewer viewer, @PathVariable UUID id) {
        return documents.access(viewer, id);
    }

    @PutMapping("/api/v1/documents/{id}/access")
    @Operation(summary = "Restrict a document to the people chosen, or open it to the space again")
    Documents.AccessView changeAccess(Viewer viewer, @PathVariable UUID id, @RequestBody Documents.AccessChange body) {
        return documents.changeAccess(viewer, id, body);
    }

    @GetMapping("/api/v1/imports/limits")
    @Operation(summary = "The size and the formats accepted for an import")
    StorageSettings.ImportLimits limits(Viewer viewer) {
        return storage.limits(viewer);
    }

    @GetMapping("/api/v1/imports/attention")
    @Operation(summary = "The documents the person imported that are still extracting or failed")
    List<Documents.AttentionItem> attention(Viewer viewer) {
        return documents.attention(viewer);
    }

    @GetMapping("/api/v1/admin/storage")
    @Operation(summary = "What the organization stores, the size of a file and the state of the extraction")
    StorageSettings.StorageView storage(Viewer viewer) {
        return storage.get(viewer);
    }

    @PatchMapping("/api/v1/admin/storage")
    @Operation(summary = "Choose the size of a file, up to the ceiling of the server")
    StorageSettings.StorageView changeStorage(Viewer viewer, @RequestBody StorageSettings.StorageChange body) {
        return storage.update(viewer, body);
    }
}
