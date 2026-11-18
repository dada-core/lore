package com.dadacore.lore.library.documents;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dadacore.lore.shared.Texts;
import com.dadacore.lore.ingestion.Extraction;
import com.dadacore.lore.ingestion.ExtractionResults;
import org.springframework.stereotype.Component;

/**
 * Records the outcome of an extraction on its version, inside the transaction that ends the job.
 * A version deleted in the meantime has taken its job with it: nothing is recorded.
 */
@Component
class ExtractionResultsAdapter implements ExtractionResults {

    private static final String MARKDOWN = "text/markdown";

    /** The place of a chunk: {@code page-12} for {@code page-12-2}, the second chunk of a long page. */
    private static final Pattern PLACE = Pattern.compile("^((?:page|h|part)-\\d+)");

    private final DocumentRepository documents;

    ExtractionResultsAdapter(DocumentRepository documents) {
        this.documents = documents;
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
        documents.searchLanguage(versionId);
        // Cleaned by the module of the extraction before this transaction.
        documents.replaceRendering(versionId, extraction.rendering());
        String title = extraction.title();
        if (title != null && !title.isBlank() && MARKDOWN.equals(version.mediaType())) {
            // The first title of a Markdown file names the document, unless someone named it already.
            String kept = Texts.cut(title, Titles.MAX_TITLE).strip();
            documents.titleFromContent(versionId, Titles.fromFilename(version.filename()), kept);
        }
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
