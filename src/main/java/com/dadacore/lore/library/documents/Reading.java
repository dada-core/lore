package com.dadacore.lore.library.documents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dadacore.lore.audit.AuditEntry;
import com.dadacore.lore.audit.AuditLog;
import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.ingestion.Rendering;
import com.dadacore.lore.shared.ApiException;
import com.dadacore.lore.shared.Checks;
import com.dadacore.lore.storage.FileStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * What the reader reads: the rendering of a version, part by part, and its original file. Both
 * are read after the right to see the document is checked, from the version found in the
 * database, never from the address alone; nothing is kept in a cache of the server.
 */
@Service
class Reading {

    static final String PDF = "application/pdf";

    /** {@code h-7-2}, the second chunk of a long place, is found at the place {@code h-7}. */
    private static final Pattern CHUNK_OF_PLACE = Pattern.compile("^((?:h|part)-\\d+)(?:-\\d+)?$");

    private static final Logger log = LoggerFactory.getLogger(Reading.class);

    private final Documents documents;
    private final DocumentRepository repository;
    private final FileStore store;
    private final AuditLog audit;

    Reading(Documents documents, DocumentRepository repository, FileStore store, AuditLog audit) {
        this.documents = documents;
        this.repository = repository;
        this.store = store;
        this.audit = audit;
    }

    /**
     * @param part      the part shown, from 1
     * @param partCount the number of parts: a long document is shown part by part
     * @param truncated the rendering stops before the end of the document
     * @param outline   the titles of the whole document and the part of each; null when not asked
     * @param anchor    the place shown: the anchor asked, or the section of the same title as in the
     *                  version {@code fromVersion}; null when there is none
     */
    record RenderingView(int number, int renderer, int part, int partCount, boolean truncated,
            @Nullable List<OutlineEntry> outline, String html, @Nullable String anchor) {
    }

    /** @param level 1 to 6, as in the document */
    record OutlineEntry(String anchor, int level, String title, int part) {
    }

    /**
     * A part of the rendering: the one asked, or the one that holds {@code anchor}, or the first.
     * With {@code fromVersion}, {@code anchor} is a place of that version: the part shown holds the
     * section of the same title in this one, if it has it (a link made for an older version).
     * {@code 409 extraction_pending} while the text is extracted; {@code 409 no_rendering} for a
     * PDF, a failure, or a version without rendering; field error {@code part:out_of_range}.
     */
    ResponseEntity<RenderingView> rendering(Viewer viewer, UUID id, int number, @Nullable Integer part,
            @Nullable String anchor, @Nullable Integer fromVersion, boolean outline) {
        Documents.Opened opened = documents.open(viewer, id);
        DocumentRepository.Version version = version(opened, number);
        if ("PENDING".equals(version.extractionStatus())) {
            throw ApiException.conflict("extraction_pending");
        }
        if (PDF.equals(version.mediaType()) || "FAILED".equals(version.extractionStatus())) {
            throw ApiException.conflict("no_rendering");
        }
        DocumentRepository.RenderingRow rendering = repository.rendering(version.id())
                .orElseThrow(() -> ApiException.conflict("no_rendering"));
        String place = anchor == null ? null : place(anchor);
        if (place != null && fromVersion != null && fromVersion != version.number()) {
            place = sameSection(opened, fromVersion, place, rendering);
        } else if (place != null && !hasPlace(rendering, place)) {
            place = null;
        }
        int shown = part != null ? part : partOf(rendering, place);
        if (shown < 1 || shown > rendering.partCount()) {
            Checks.start().fail("part", "out_of_range", Map.of("min", 1, "max", rendering.partCount())).orThrow();
        }
        String html = repository.renderingPart(version.id(), shown).orElseThrow(() -> ApiException.conflict("no_rendering"));
        List<OutlineEntry> entries = !outline ? null : rendering.places().stream()
                .filter(entry -> entry.title() != null && entry.level() > 0)
                .map(entry -> new OutlineEntry(entry.anchor(), entry.level(), entry.title(), entry.part()))
                .toList();
        // Weak: Tomcat compresses the answer, as a strong one would forbid.
        String etag = "W/\"" + version.id() + "-" + rendering.renderer() + "-" + rendering.createdAt().toEpochMilli()
                + "-" + shown + (outline ? "-o" : "") + (place == null ? "" : "-" + place) + "\"";
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache().cachePrivate())
                .eTag(etag)
                .body(new RenderingView(version.number(), rendering.renderer(), shown, rendering.partCount(),
                        rendering.truncated(), entries, html, place));
    }

    /**
     * The section of this rendering with the title the place has in another version, taken in the
     * same rank among the sections of that title; null when the other version or the title is gone.
     */
    private @Nullable String sameSection(Documents.Opened opened, int fromVersion, String place,
            DocumentRepository.RenderingRow rendering) {
        DocumentRepository.Version other = repository.version(opened.row().id(), fromVersion).orElse(null);
        DocumentRepository.RenderingRow before = other == null ? null : repository.rendering(other.id()).orElse(null);
        if (before == null) {
            return null;
        }
        String title = null;
        int rank = 0;
        for (Rendering.Place candidate : before.places()) {
            if (candidate.title() == null) {
                continue;
            }
            if (candidate.anchor().equals(place)) {
                title = plain(candidate.title());
                break;
            }
        }
        if (title == null) {
            return null;
        }
        for (Rendering.Place candidate : before.places()) {
            if (candidate.anchor().equals(place)) {
                break;
            }
            if (candidate.title() != null && plain(candidate.title()).equals(title)) {
                rank++;
            }
        }
        String wanted = title;
        List<Rendering.Place> same = rendering.places().stream()
                .filter(candidate -> candidate.title() != null && plain(candidate.title()).equals(wanted))
                .toList();
        if (same.isEmpty()) {
            return null;
        }
        return same.get(Math.min(rank, same.size() - 1)).anchor();
    }

    private static boolean hasPlace(DocumentRepository.RenderingRow rendering, String place) {
        return rendering.places().stream().anyMatch(candidate -> candidate.anchor().equals(place));
    }

    /** A title compared without its case nor its spaces. */
    private static String plain(String title) {
        return title.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** The place of an anchor: {@code h-7} for {@code h-7-2}; null for an anchor of no place. */
    private static @Nullable String place(String anchor) {
        Matcher place = CHUNK_OF_PLACE.matcher(anchor.strip().toLowerCase(Locale.ROOT));
        return place.matches() ? place.group(1) : null;
    }

    /**
     * The original file, in one piece or by ranges. Field error {@code disposition:invalid_value},
     * also for {@code inline} on another format than a PDF; {@code 410 file_missing}.
     */
    void file(Viewer viewer, UUID id, int number, @Nullable String disposition, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        Documents.Opened opened = documents.open(viewer, id);
        DocumentRepository.Version version = version(opened, number);
        boolean inline = "inline".equals(disposition);
        if ((disposition != null && !inline && !"attachment".equals(disposition)) || (inline && !PDF.equals(version.mediaType()))) {
            throw Checks.rejected("disposition", "invalid_value");
        }
        UUID organizationId = opened.space().organizationId();
        Path path = store.path(organizationId, version.sha256());
        long size;
        try {
            size = Files.size(path);
        } catch (IOException missing) {
            // A backup restored without its files, or a file deleted by hand: the operator must know.
            log.warn("The file of a version is missing: organization {}, SHA-256 {}", organizationId, version.sha256());
            throw new ApiException(HttpStatus.GONE, "file_missing");
        }
        ContentDisposition named = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(version.filename(), StandardCharsets.UTF_8)
                .build();
        OriginalFile original = new OriginalFile(path, size, version.mediaType(), version.sha256(), version.createdAt(), named);
        original.prepare(request, response);
        if (original.hasBody(request)) {
            try {
                original.open();
            } catch (java.nio.file.NoSuchFileException missing) {
                // Deleted between its check and its reading: nothing was sent yet.
                log.warn("The file of a version is missing: organization {}, SHA-256 {}", organizationId, version.sha256());
                response.reset();
                throw new ApiException(HttpStatus.GONE, "file_missing");
            } catch (IOException unreadable) {
                // Unreadable (rights on the disk, a lock): an error of the server, not an empty file.
                response.reset();
                throw new IllegalStateException("The file of a version cannot be read: organization " + organizationId
                        + ", SHA-256 " + version.sha256(), unreadable);
            }
        }
        try {
            // A download, not the reading of a PDF in the page or in a tab (a view, recorded already),
            // nor the resumption of a download that stopped.
            if (!inline && "GET".equals(request.getMethod()) && original.fromTheStart()) {
                audit.record(AuditEntry.of(organizationId, viewer.userId(), "document.downloaded")
                        .resource("document", opened.row().id())
                        .ip(viewer.ip())
                        .detail("title", opened.row().title())
                        .detail("space", opened.space().name())
                        .detail("spaceId", opened.space().id())
                        .detail("version", version.number())
                        .detail("filename", version.filename())
                        .detail("sizeBytes", size)
                        .build());
            }
            original.send(request, response);
        } catch (IOException gone) {
            // The browser stopped reading: a download cancelled, or pdf.js that has what it needs.
            log.debug("Download of a version stopped: {}", gone.toString());
        } finally {
            original.close();
        }
    }

    /** The version asked, which must be one of the document. */
    private DocumentRepository.Version version(Documents.Opened opened, int number) {
        DocumentRepository.Version current = opened.row().version();
        if (number == current.number()) {
            return current;
        }
        return repository.version(opened.row().id(), number).orElseThrow(ApiException::notFound);
    }

    /** The part that holds a place; the first for none, or for a place the document does not have. */
    private static int partOf(DocumentRepository.RenderingRow rendering, @Nullable String place) {
        if (place == null) {
            return 1;
        }
        return rendering.places().stream()
                .filter(candidate -> candidate.anchor().equals(place))
                .mapToInt(Rendering.Place::part)
                .findFirst()
                .orElse(1);
    }
}
