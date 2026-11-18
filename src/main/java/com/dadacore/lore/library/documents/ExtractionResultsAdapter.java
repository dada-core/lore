package com.dadacore.lore.library.documents;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dadacore.lore.audit.AuditEntry;
import com.dadacore.lore.audit.AuditLog;
import com.dadacore.lore.shared.Texts;
import com.dadacore.lore.ingestion.Extraction;
import com.dadacore.lore.ingestion.ExtractionResults;
import org.springframework.stereotype.Component;

/**
 * Records the outcome of an extraction on its version, inside the transaction that ends the job.
 * A version deleted in the meantime has taken its job with it: nothing is recorded.
 *
 * <p>A new version becomes the current one here, once its text is extracted: readers and the search
 * go from the previous version to this one at once, in this transaction. A version older than the
 * current one, which ended after it, stays in the history.
 */
@Component
class ExtractionResultsAdapter implements ExtractionResults {

    private static final String MARKDOWN = "text/markdown";

    /** The place of a chunk: {@code page-12} for {@code page-12-2}, the second chunk of a long page. */
    private static final Pattern PLACE = Pattern.compile("^((?:page|h|part)-\\d+)");

    private final DocumentRepository documents;
    private final AuditLog audit;

    ExtractionResultsAdapter(DocumentRepository documents, AuditLog audit) {
        this.documents = documents;
        this.audit = audit;
    }

    @Override
    public void extracted(UUID versionId, Extraction extraction) {
        Optional<DocumentRepository.Version> found = documents.version(versionId);
        if (found.isEmpty()) {
            return;
        }
        DocumentRepository.Version version = found.get();
        // Sections are the places of the document (pages, parts under a title), not its chunks.
        int sections = (int) extraction.chunks().stream().map(chunk -> place(chunk.anchor())).distinct().count();
        // The language detected, unless a person chose one for this version.
        String language = documents.extracted(versionId, extraction.scanned() ? "SCANNED" : "INDEXED",
                extraction.language(), extraction.pageCount(), sections, extraction.truncated());
        documents.replaceChunks(versionId, extraction.chunks(), language);
        // Cleaned by the module of the extraction before this transaction.
        documents.replaceRendering(versionId, extraction.rendering());
        // The document locked only now: a change of its title does not wait for the passages written.
        Optional<DocumentRepository.Ended> ended = documents.ended(versionId);
        if (ended.isEmpty()) {
            return;
        }
        DocumentRepository.Ended document = ended.get();
        if (document.currentVersionId().equals(versionId)) {
            // A first version, or the current one extracted again.
            documents.searchLanguage(versionId);
            if (version.number() == 1) {
                documents.changeRatio(versionId, 1f);
            }
        } else if (version.number() > document.currentNumber()) {
            becomesCurrent(version, document);
        } else {
            // Older than the current version, which ended first: kept in the history, its rendering and
            // its file read when it is shown; its passages, which nothing reads, are not kept.
            documents.deleteChunks(versionId);
        }
        String title = extraction.title();
        if (title != null && !title.isBlank() && MARKDOWN.equals(version.mediaType())) {
            // The first title of a Markdown file names the document, unless someone named it already.
            String kept = Texts.cut(title, Titles.MAX_TITLE).strip();
            documents.titleFromContent(versionId, Titles.fromFilename(version.filename()), kept);
        }
    }

    /**
     * The version takes the place of the current one: the share of passages changed, then the
     * switch, the status asked for at its import, and the passages of the previous version deleted.
     */
    private void becomesCurrent(DocumentRepository.Version version, DocumentRepository.Ended document) {
        documents.changeRatio(version.id(), documents.changeRatio(document.currentVersionId(), version.id()));
        documents.makeCurrent(document.documentId(), version.id());
        String asked = document.statusWhenCurrent();
        // Not when the status changed since the import: a document archived meanwhile stays archived.
        if (asked != null && !document.statusChangedSince() && !"ARCHIVED".equals(document.status())
                && !asked.equals(document.status())) {
            documents.changeStatus(document.documentId(), asked, null, true);
            audit.record(AuditEntry.of(document.organizationId(), version.authorId(), "document.status_changed")
                    .resource("document", document.documentId())
                    .detail("title", document.title())
                    .detail("space", document.spaceName())
                    .detail("spaceId", document.spaceId())
                    .detail("before", document.status())
                    .detail("after", asked)
                    .detail("version", version.number())
                    .build());
        }
        documents.deleteChunks(document.currentVersionId());
    }

    @Override
    public void failed(UUID versionId, String code) {
        documents.failed(versionId, code);
        // A failed version shows its banner and its file only.
        documents.replaceRendering(versionId, null);
    }

    private static String place(String anchor) {
        Matcher matcher = PLACE.matcher(anchor);
        return matcher.find() ? matcher.group(1) : anchor;
    }
}
