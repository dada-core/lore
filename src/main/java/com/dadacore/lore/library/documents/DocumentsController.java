package com.dadacore.lore.library.documents;

import java.io.IOException;
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
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * {@code 409 space_archived}; an archived document any change but its status and its deletion,
 * {@code 409 document_archived}.
 */
@RestController
@Tag(name = "Documents")
class DocumentsController {

    private final Documents documents;
    private final Imports imports;
    private final StorageSettings storage;
    private final Reading reading;
    private final DocumentActivity activity;

    DocumentsController(Documents documents, Imports imports, StorageSettings storage, Reading reading,
            DocumentActivity activity) {
        this.documents = documents;
        this.imports = imports;
        this.storage = storage;
        this.reading = reading;
        this.activity = activity;
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

    /**
     * @param file   the file of the new version: PDF, Word (.docx), Markdown or text
     * @param note   what changes, 500 characters at most
     * @param status {@code DRAFT} or {@code PUBLISHED}: the status the document takes when the version
     *               becomes current; none: unchanged
     */
    record VersionForm(@Schema(type = "string", format = "binary") String file, @Nullable String note,
            @Nullable String status) {
    }

    @GetMapping("/api/v1/spaces/{spaceId}/documents")
    @Operation(summary = "A page of the documents of a space the person can see, by folder then in the order asked: "
            + "sort is updated (default, most recent first), title, category, status or size, direction asc or desc; "
            + "folder=none lists those without a folder, ids (100 at most) follows given documents, status (several) "
            + "the documents of these statuses; without status, the archived ones are left out")
    PageResponse<DocumentItem> list(Viewer viewer, @PathVariable UUID spaceId,
            @RequestParam(required = false) @Nullable String folder,
            @RequestParam(required = false) @Nullable Boolean restricted,
            @RequestParam(required = false) @Nullable List<UUID> ids,
            @RequestParam(required = false) @Nullable List<String> status,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String direction,
            @RequestParam(required = false) @Nullable Integer page,
            @RequestParam(required = false) @Nullable Integer size) {
        return documents.list(viewer, spaceId, folder, restricted, ids, status, sort, direction, PageQuery.of(page, size));
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
    @Operation(summary = "A document, its current version and what the person may do with it; "
            + "its opening is recorded in the audit log once an hour, with the version shown (version, an older one)")
    DocumentView get(Viewer viewer, @PathVariable UUID id, @RequestParam(required = false) @Nullable Integer version) {
        return documents.get(viewer, id, version);
    }

    @PostMapping(path = "/api/v1/documents/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Import a new version of a document, which becomes current once its text is extracted; "
            + "409 same_content (the latest version has this file), 409 document_archived",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(implementation = VersionForm.class))))
    DocumentView newVersion(Viewer viewer, @PathVariable UUID id, HttpServletRequest request) {
        return imports.newVersion(viewer, id, request);
    }

    @GetMapping("/api/v1/documents/{id}/versions")
    @Operation(summary = "The versions of a document, the latest first (50 by default, 200 at most)")
    PageResponse<DocumentViews.VersionView> versions(Viewer viewer, @PathVariable UUID id,
            @RequestParam(required = false) @Nullable Integer page,
            @RequestParam(required = false) @Nullable Integer size) {
        return documents.versions(viewer, id, PageQuery.of(page, size));
    }

    @GetMapping("/api/v1/documents/{id}/versions/{number}")
    @Operation(summary = "A version of a document")
    DocumentViews.VersionView version(Viewer viewer, @PathVariable UUID id, @PathVariable int number) {
        return documents.version(viewer, id, number);
    }

    @PutMapping("/api/v1/documents/{id}/status")
    @Operation(summary = "Change the status of a document: Draft, Published, Obsolete (with replacedBy, a document "
            + "the person reads) for contributors; Archived, and out of it, for the maintainers of the space")
    DocumentView changeStatus(Viewer viewer, @PathVariable UUID id, @RequestBody Documents.StatusChange body) {
        return documents.changeStatus(viewer, id, body);
    }

    @GetMapping("/api/v1/documents/{id}/activity")
    @Operation(summary = "What was done to a document, the latest first, without its views and downloads: "
            + "before (the cursor next of the previous page), size (20 by default, 50 at most)")
    DocumentActivity.ActivityPage activity(Viewer viewer, @PathVariable UUID id,
            @RequestParam(required = false) @Nullable String before,
            @RequestParam(required = false) @Nullable Integer size) {
        return activity.document(viewer, id, before, size);
    }

    @GetMapping("/api/v1/activity")
    @Operation(summary = "The 8 latest imports, new versions and changes of status of the documents the person sees")
    List<DocumentActivity.HomeItem> recentActivity(Viewer viewer) {
        return activity.home(viewer);
    }

    @GetMapping("/api/v1/documents/recent")
    @Operation(summary = "The documents the person opened last and still sees, most recent first (size: 3 by default, 10 at most)")
    List<Documents.RecentDocument> recent(Viewer viewer, @RequestParam(required = false) @Nullable Integer size) {
        return documents.recent(viewer, size);
    }

    @GetMapping("/api/v1/documents/{id}/versions/{number}/rendering")
    @Operation(summary = "A version laid out for reading (Markdown, Word, text), cleaned: the part asked (part), "
            + "or the part that holds an anchor (anchor), or the first; with fromVersion, anchor is a place of that "
            + "version, found here by its title; the outline of the whole document unless "
            + "outline=false. 409 extraction_pending, 409 no_rendering (a PDF, a failure)")
    ResponseEntity<Reading.RenderingView> rendering(Viewer viewer, @PathVariable UUID id, @PathVariable int number,
            @RequestParam(required = false) @Nullable Integer part,
            @RequestParam(required = false) @Nullable String anchor,
            @RequestParam(required = false) @Nullable Integer fromVersion,
            @RequestParam(required = false, defaultValue = "true") boolean outline) {
        return reading.rendering(viewer, id, number, part, anchor, fromVersion, outline);
    }

    @GetMapping("/api/v1/documents/{id}/versions/{number}/file")
    @Operation(summary = "The original file of a version, under its name: attachment (default), or inline for a PDF; "
            + "ranges (206, 416), If-Range, If-None-Match (304), 410 file_missing. A download is recorded in the audit log")
    void file(Viewer viewer, @PathVariable UUID id, @PathVariable int number,
            @RequestParam(required = false) @Nullable String disposition, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        reading.file(viewer, id, number, disposition, request, response);
    }

    @PutMapping("/api/v1/documents/{id}/owner")
    @Operation(summary = "Hand the document over to another member of the space, at least a contributor: "
            + "its maintainers, or its owner")
    DocumentView changeOwner(Viewer viewer, @PathVariable UUID id, @RequestBody Documents.OwnerChange body) {
        return documents.changeOwner(viewer, id, body);
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
    @Operation(summary = "Try the extraction of the latest version again after a passing failure; 409 not_retryable otherwise")
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
    @Operation(summary = "The versions the person imported that are still extracting or failed, first imports and "
            + "new versions, as long as no later version became current")
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
