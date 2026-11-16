package com.dadacore.lore.library.documents;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.dadacore.lore.access.DocumentAccess;
import com.dadacore.lore.identity.Viewer;
import org.springframework.stereotype.Component;

/** The number of documents a person sees in each space, for the lists of spaces. */
@Component
public class DocumentCounts {

    private final DocumentRepository documents;
    private final DocumentAccess access;

    DocumentCounts(DocumentRepository documents, DocumentAccess access) {
        this.documents = documents;
        this.access = access;
    }

    public Map<UUID, Long> counts(Viewer viewer, Collection<UUID> spaceIds) {
        return documents.countBySpace(spaceIds, access.readableDocuments(viewer, "d", "s"));
    }
}
