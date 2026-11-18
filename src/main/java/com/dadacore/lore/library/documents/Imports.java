package com.dadacore.lore.library.documents;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.dadacore.lore.LoreProperties;
import com.dadacore.lore.access.DocumentGrants;
import com.dadacore.lore.access.SpaceRole;
import com.dadacore.lore.audit.AuditEntry;
import com.dadacore.lore.audit.AuditLog;
import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.ingestion.ExtractionQueue;
import com.dadacore.lore.library.documents.DocumentViews.DocumentView;
import com.dadacore.lore.library.spaces.Space;
import com.dadacore.lore.library.spaces.Spaces;
import com.dadacore.lore.shared.ApiException;
import com.dadacore.lore.shared.Checks;
import com.dadacore.lore.storage.FileStore;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * The import of one file, as a new document or as a new version of one, in this order:
 *
 * <ol>
 *   <li>before the body is read: the space ({@code 404}), the role ({@code 403 contributor_only}),
 *       the active space ({@code 409 space_archived}) and the size announced ({@code 413});</li>
 *   <li>the file is received in the temporary folder (SHA-256, size, first bytes), and its format
 *       and size checked;</li>
 *   <li>one transaction: the space locked, the folder and the people chosen checked, the file put
 *       in its place unless the same content is there, the document and its version 1, its
 *       access, the extraction job, the audit log;</li>
 *   <li>after it: the workers are woken up, or the file is deleted if the import was cancelled
 *       and nothing else uses it.</li>
 * </ol>
 *
 * <p>A new version is checked the same way, on its document: contributors only, an active space, a
 * document that is not archived ({@code 409 document_archived}), a file that differs from the latest
 * version ({@code 409 same_content}). It becomes current once its text is extracted.
 */
@Service
class Imports {

    /** The disk keeps at least this much free after a file is written. */
    static final long MIN_FREE_BYTES = 100L * 1024 * 1024;

    private final Spaces spaces;
    private final DocumentRepository documents;
    private final Documents reads;
    private final DocumentGrants grants;
    private final FileStore store;
    private final ImportSettings settings;
    private final ExtractionQueue queue;
    private final FileJanitor janitor;
    private final AuditLog audit;
    private final TransactionTemplate transaction;

    Imports(Spaces spaces, DocumentRepository documents, Documents reads, DocumentGrants grants, FileStore store,
            ImportSettings settings, ExtractionQueue queue, FileJanitor janitor, AuditLog audit,
            TransactionTemplate transaction) {
        this.spaces = spaces;
        this.documents = documents;
        this.reads = reads;
        this.grants = grants;
        this.store = store;
        this.settings = settings;
        this.queue = queue;
        this.janitor = janitor;
        this.audit = audit;
        this.transaction = transaction;
    }

    /** What a new version may say about itself, at most. */
    static final int MAX_NOTE = 500;

    private static final java.util.regex.Pattern CONTROL_BUT_LINES = java.util.regex.Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");

    /** The fields of the form, checked. */
    private record Form(@Nullable UUID folderId, String status, @Nullable String category, boolean restricted,
            List<UUID> userIds) {
    }

    /**
     * The fields of the form of a new version, checked.
     *
     * @param status the status the document takes when the version becomes current; null: unchanged
     */
    private record VersionForm(@Nullable String note, @Nullable String status) {
    }

    /** A file received and checked, not yet in its place. */
    private record Upload(FileStore.Received received, String filename, Formats format) {
    }

    /**
     * Field errors on {@code file} ({@code required}, {@code empty_file}, {@code unsupported_format},
     * {@code legacy_word}, {@code content_mismatch}), {@code folderId} ({@code unknown_folder}),
     * {@code status} and {@code category} ({@code invalid_value}), {@code userIds}
     * ({@code not_space_member}); {@code 413 file_too_large}, {@code 507 storage_full}.
     */
    DocumentView importFile(Viewer viewer, UUID spaceId, HttpServletRequest request) {
        Space space = spaces.open(viewer, spaceId, SpaceRole.CONTRIBUTOR).requireActive().space();
        int megabytes = announced(space.organizationId(), request);
        Part part = part(request, megabytes);
        String filename = Titles.filename(part.getSubmittedFileName());
        Form form = form(request);
        Upload upload = receive(space.organizationId(), part, filename, megabytes);
        FileStore.Received received = upload.received();
        UUID documentId;
        AtomicBoolean placed = new AtomicBoolean();
        try {
            documentId = transaction.execute(status -> create(viewer, spaceId, received, filename, upload.format(), form,
                    placed));
        } catch (RuntimeException e) {
            undo(space.organizationId(), received, placed);
            throw e;
        }
        Documents.Opened opened = reads.open(viewer, documentId);
        DocumentViews.DocumentLink duplicate = documents
                .sameContent(opened.space().id(), received.sha256(), documentId, reads.readable(viewer))
                .map(row -> new DocumentViews.DocumentLink(row.id(), row.title()))
                .orElse(null);
        return reads.view(viewer, opened, duplicate);
    }

    /**
     * A new version of a document, extracted next; the current one stays until then. Field errors
     * on {@code file} as for an import, {@code note:too_long}, {@code status:invalid_value};
     * {@code 409 same_content} (with {@code version}), {@code 409 document_archived},
     * {@code 409 space_archived}, {@code 413 file_too_large}, {@code 507 storage_full}.
     */
    DocumentView newVersion(Viewer viewer, UUID documentId, HttpServletRequest request) {
        Documents.Opened opened = reads.open(viewer, documentId);
        if (!opened.role().includes(SpaceRole.CONTRIBUTOR)) {
            throw ApiException.forbidden("contributor_only");
        }
        opened.space().ref().requireActive();
        Documents.writable(opened);
        UUID organizationId = opened.space().organizationId();
        int megabytes = announced(organizationId, request);
        Part part = part(request, megabytes);
        String filename = Titles.filename(part.getSubmittedFileName());
        VersionForm form = versionForm(request);
        Upload upload = receive(organizationId, part, filename, megabytes);
        AtomicBoolean placed = new AtomicBoolean();
        try {
            transaction.executeWithoutResult(status -> createVersion(viewer, documentId, upload, form, placed));
        } catch (RuntimeException e) {
            undo(organizationId, upload.received(), placed);
            throw e;
        }
        return reads.view(viewer, reads.open(viewer, documentId), null);
    }

    private void createVersion(Viewer viewer, UUID documentId, Upload upload, VersionForm form, AtomicBoolean placed) {
        Documents.Opened opened = Documents.writable(reads.openForChange(viewer, documentId, SpaceRole.CONTRIBUTOR));
        DocumentRepository.Row row = opened.row();
        Space space = opened.space();
        FileStore.Received received = upload.received();
        // The same file as the latest version, current or not, would add nothing; an older one is a way back.
        DocumentRepository.Version latest = reads.latest(row);
        if (latest.sha256().equals(received.sha256())) {
            throw ApiException.conflict("same_content").with("version", latest.number());
        }
        documents.lockContent(space.organizationId(), received.sha256());
        DocumentRepository.Version current = row.version();
        int number = documents.nextNumber(row.id());
        UUID versionId = documents.insertNewVersion(new DocumentRepository.NewVersion(row.id(), number, received.sha256(),
                upload.filename(), upload.format().mediaType(), received.size(), viewer.userId(), form.note(), form.status(),
                current.languageChosen() ? current.language() : null));
        try {
            store.keep(received);
            placed.set(true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        queue.enqueue(versionId, space.organizationId(), received.sha256(), upload.format().mediaType());
        spaces.touch(space.id());
        audit.record(AuditEntry.of(space.organizationId(), viewer.userId(), "document.version_imported")
                .resource("document", row.id())
                .ip(viewer.ip())
                .detail("title", row.title())
                .detail("space", space.name())
                .detail("spaceId", space.id())
                .detail("version", number)
                .detail("previous", current.number())
                .detail("filename", upload.filename())
                .detail("size", received.size())
                .detail("status", form.status())
                .build());
    }

    /**
     * The size of a file the organization accepts, in megabytes, checked against the size the request
     * announces, and the free space of the disk: before anything is read.
     */
    private int announced(UUID organizationId, HttpServletRequest request) {
        int megabytes = settings.megabytes(organizationId);
        long maxBytes = DataSize.ofMegabytes(megabytes).toBytes();
        if (request.getContentLengthLong() > maxBytes + LoreProperties.UPLOAD_FORM_MARGIN.toBytes()) {
            throw tooLarge(megabytes);
        }
        if (store.freeSpace() < MIN_FREE_BYTES + Math.max(0, request.getContentLengthLong())) {
            throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE, "storage_full");
        }
        return megabytes;
    }

    /** The file received in the temporary folder, then checked: deleted again when it is refused. */
    private Upload receive(UUID organizationId, Part part, String filename, int megabytes) {
        long maxBytes = DataSize.ofMegabytes(megabytes).toBytes();
        FileStore.Received received;
        Path upload = store.newTemporaryFile();
        try {
            // Moved, not copied: the server wrote the part in the same temporary folder.
            part.write(upload.toString());
            received = store.adopt(organizationId, upload, maxBytes);
        } catch (FileStore.TooLarge e) {
            throw tooLarge(megabytes);
        } catch (IOException e) {
            deleteQuietly(upload);
            if (store.freeSpace() < MIN_FREE_BYTES) {
                throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE, "storage_full");
            }
            throw new UncheckedIOException(e);
        } finally {
            deleteQuietly(part);
        }
        try {
            if (received.size() == 0) {
                throw Checks.rejected("file", "empty_file");
            }
            Formats format = Formats.detect(filename, received.head());
            if (store.freeSpace() < MIN_FREE_BYTES) {
                throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE, "storage_full");
            }
            return new Upload(received, filename, format);
        } catch (RuntimeException e) {
            store.discard(received);
            throw e;
        }
    }

    /** A file received for an import that failed: deleted, and from its place unless something else uses it. */
    private void undo(UUID organizationId, FileStore.Received received, AtomicBoolean placed) {
        store.discard(received);
        if (placed.get()) {
            // Put in its place before the transaction was cancelled: deleted unless used already.
            janitor.deleteIfUnused(organizationId, received.sha256());
        }
    }

    private UUID create(Viewer viewer, UUID spaceId, FileStore.Received received, String filename, Formats format,
            Form form, AtomicBoolean placed) {
        Space space = spaces.openForChange(viewer, spaceId, SpaceRole.CONTRIBUTOR).requireActive().space();
        if (form.folderId() != null && !documents.folderOf(space.id(), form.folderId())) {
            throw Checks.rejected("folderId", "unknown_folder");
        }
        documents.lockContent(space.organizationId(), received.sha256());
        String title = Titles.fromFilename(filename);
        UUID documentId = documents.insert(new DocumentRepository.NewDocument(space.id(), form.folderId(), title,
                form.category(), form.status(), form.restricted(), viewer.userId()));
        UUID versionId = documents.insertVersion(documentId, 1, received.sha256(), filename, format.mediaType(),
                received.size(), viewer.userId());
        if (form.restricted()) {
            // Checks that the people chosen are members of the space, before the file is put in place.
            grants.replace(documentId, space.ref(), form.userIds(), viewer.userId());
        }
        try {
            store.keep(received);
            placed.set(true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        queue.enqueue(versionId, space.organizationId(), received.sha256(), format.mediaType());
        spaces.touch(space.id());
        audit.record(AuditEntry.of(space.organizationId(), viewer.userId(), "document.imported")
                .resource("document", documentId)
                .ip(viewer.ip())
                .detail("title", title)
                .detail("filename", filename)
                .detail("size", received.size())
                .detail("space", space.name())
                .detail("spaceId", space.id())
                .detail("folderId", form.folderId())
                .detail("restricted", form.restricted())
                .build());
        return documentId;
    }

    /** The file of the form; beyond the ceiling of the server, the request stops there. */
    private static Part part(HttpServletRequest request, int megabytes) {
        try {
            Part part = request.getPart("file");
            if (part == null || part.getSubmittedFileName() == null) {
                throw Checks.rejected("file", "required");
            }
            return part;
        } catch (MaxUploadSizeExceededException e) {
            throw tooLarge(megabytes);
        } catch (IllegalStateException e) {
            // Tomcat stops reading at one of its limits: the ceiling of the size (LORE_MAX_UPLOAD_SIZE),
            // or the number of fields of the form, which is no matter of size.
            if (sizeLimit(e)) {
                throw tooLarge(megabytes);
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request");
        } catch (ServletException | IOException e) {
            throw Checks.rejected("file", "required");
        }
    }

    /** A limit of size of Tomcat (FileSizeLimitExceededException, SizeLimitExceededException) in the causes. */
    private static boolean sizeLimit(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getClass().getSimpleName().contains("SizeLimitExceeded")) {
                return true;
            }
        }
        return false;
    }

    private static Form form(HttpServletRequest request) {
        Checks checks = Checks.start();
        UUID folderId = null;
        String folder = request.getParameter("folderId");
        if (folder != null && !folder.isBlank()) {
            try {
                folderId = UUID.fromString(folder.strip());
            } catch (IllegalArgumentException e) {
                checks.fail("folderId", "unknown_folder");
            }
        }
        String requestedStatus = request.getParameter("status");
        Documents.ImportStatus status = requestedStatus == null || requestedStatus.isBlank()
                ? Documents.ImportStatus.DRAFT
                : checks.oneOf("status", requestedStatus, Documents.ImportStatus.class);
        String requestedCategory = request.getParameter("category");
        Documents.Category category = requestedCategory == null || requestedCategory.isBlank()
                ? null
                : checks.oneOf("category", requestedCategory, Documents.Category.class);
        boolean restricted = Boolean.parseBoolean(request.getParameter("restricted"));
        List<UUID> userIds = new ArrayList<>();
        String[] values = request.getParameterValues("userIds");
        if (values != null) {
            for (String value : values) {
                for (String id : value.split(",")) {
                    if (id.isBlank()) {
                        continue;
                    }
                    try {
                        userIds.add(UUID.fromString(id.strip()));
                    } catch (IllegalArgumentException e) {
                        checks.fail("userIds", "not_space_member");
                    }
                }
            }
        }
        checks.orThrow();
        return new Form(folderId, status == null ? "DRAFT" : status.name(), category == null ? null : category.name(),
                restricted, userIds.stream().distinct().toList());
    }

    private static VersionForm versionForm(HttpServletRequest request) {
        Checks checks = Checks.start();
        String note = request.getParameter("note");
        String written = note == null ? ""
                : CONTROL_BUT_LINES.matcher(note.replace("\r\n", "\n")).replaceAll(" ").strip();
        if (written.length() > MAX_NOTE) {
            checks.fail("note", "too_long", java.util.Map.of("max", MAX_NOTE));
        }
        String requested = request.getParameter("status");
        Documents.ImportStatus status = requested == null || requested.isBlank() ? null
                : checks.oneOf("status", requested.strip().toUpperCase(java.util.Locale.ROOT), Documents.ImportStatus.class);
        checks.orThrow();
        return new VersionForm(written.isEmpty() ? null : written, status == null ? null : status.name());
    }

    private static ApiException tooLarge(int megabytes) {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large").with("maxMegabytes", megabytes);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // Removed from tmp/ at the next start anyway.
        }
    }

    private static void deleteQuietly(Part part) {
        try {
            part.delete();
        } catch (IOException e) {
            // The server removes it at the end of the request anyway.
        }
    }
}
