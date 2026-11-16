package com.dadacore.lore.library.documents;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.dadacore.lore.audit.AuditEntry;
import com.dadacore.lore.audit.AuditLog;
import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.ingestion.ExtractionHealth;
import com.dadacore.lore.shared.Checks;
import com.dadacore.lore.storage.FileStore;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.transaction.annotation.Transactional;

/** Administration › Storage and import: what is stored, the size of a file, the extraction. */
@Service
class StorageSettings {

    private final DocumentRepository documents;
    private final ImportSettings settings;
    private final FileStore store;
    private final ObjectProvider<ExtractionHealth> extraction;
    private final AuditLog audit;

    StorageSettings(DocumentRepository documents, ImportSettings settings, FileStore store,
            ObjectProvider<ExtractionHealth> extraction, AuditLog audit) {
        this.documents = documents;
        this.settings = settings;
        this.store = store;
        this.extraction = extraction;
        this.audit = audit;
    }

    /**
     * @param usedBytes                 the files of the organization, each content counted once
     * @param freeBytes                 the space left on the disk of the files
     * @param maxUploadMegabytes        the size of a file in force
     * @param maxUploadCeilingMegabytes the ceiling set by {@code LORE_MAX_UPLOAD_SIZE}
     * @param filesPath                 the folder of the files on the server ({@code LORE_FILES_PATH})
     */
    record StorageView(long usedBytes, long freeBytes, long documents, long inProgress, long scanned, long failed,
            int maxUploadMegabytes, int maxUploadCeilingMegabytes, int defaultMaxUploadMegabytes,
            List<String> extensions, ExtractionView extraction, String filesPath) {
    }

    /**
     * @param active  false when the extraction is turned off on this server
     * @param problem why the last extraction process could not start, or null
     */
    record ExtractionView(boolean active, int workers, @Nullable String problem) {
    }

    record StorageChange(@Nullable Integer maxUploadMegabytes) {
    }

    /** What the interface needs before an import. */
    record ImportLimits(int maxMegabytes, long maxBytes, List<String> extensions) {
    }

    StorageView get(Viewer viewer) {
        viewer.requireAdmin();
        return view(viewer);
    }

    /** Field error {@code maxUploadMegabytes:out_of_range}, below 1 or above the ceiling. */
    @Transactional
    StorageView update(Viewer viewer, StorageChange change) {
        viewer.requireAdmin();
        Integer requested = change.maxUploadMegabytes();
        if (requested != null) {
            int ceiling = settings.ceilingMegabytes();
            if (requested < ImportSettings.MIN_MEGABYTES || requested > ceiling) {
                Checks.start()
                        .fail("maxUploadMegabytes", "out_of_range", Map.of("min", ImportSettings.MIN_MEGABYTES, "max", ceiling))
                        .orThrow();
            }
            // Compared with the choice saved, not with the size in force: equal to the default or to
            // the ceiling today, it must stay the choice once they change.
            int before = settings.megabytes(viewer.organizationId());
            if (!settings.chosenMegabytes(viewer.organizationId()).equals(Optional.of(requested))) {
                settings.choose(viewer.organizationId(), requested);
                audit.record(AuditEntry.of(viewer.organizationId(), viewer.userId(), "import.settings_changed")
                        .resource("organization", viewer.organizationId())
                        .ip(viewer.ip())
                        .detail("before", before)
                        .detail("after", requested)
                        .build());
            }
        }
        return view(viewer);
    }

    ImportLimits limits(Viewer viewer) {
        int megabytes = settings.megabytes(viewer.organizationId());
        return new ImportLimits(megabytes, DataSize.ofMegabytes(megabytes).toBytes(), Formats.EXTENSIONS);
    }

    private StorageView view(Viewer viewer) {
        DocumentRepository.StorageUse use = documents.use(viewer.organizationId());
        ExtractionHealth health = extraction.getIfAvailable();
        ExtractionView extractionView = health == null
                ? new ExtractionView(false, 0, null)
                : new ExtractionView(true, health.state().workers(), health.state().problem());
        return new StorageView(use.usedBytes(), store.freeSpace(), use.documents(), use.inProgress(), use.scanned(),
                use.failed(), settings.megabytes(viewer.organizationId()), settings.ceilingMegabytes(),
                ImportSettings.DEFAULT_MEGABYTES, Formats.EXTENSIONS, extractionView, store.root().toString());
    }
}
