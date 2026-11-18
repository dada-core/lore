package com.dadacore.lore.identity.organization;

import java.io.IOException;

import com.dadacore.lore.identity.Viewer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administration › Audit log. Reserved to owners and administrators ({@code 403 admin_only} otherwise). */
@RestController
@Tag(name = "Administration")
class AuditJournalController {

    private final AuditJournal journal;

    AuditJournalController(AuditJournal journal) {
        this.journal = journal;
    }

    @GetMapping("/api/v1/admin/audit")
    @Operation(summary = "The events of the organization, the latest first: category (view, download, import, status, "
            + "change, deletion, rights, members, sign_in, organization, other), actor (an identifier, or system), "
            + "from and to (instants), before (the cursor next of the previous page), size (50 by default, 200 at most)")
    AuditJournal.AuditPage page(Viewer viewer, @RequestParam(required = false) @Nullable String category,
            @RequestParam(required = false) @Nullable String actor, @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to, @RequestParam(required = false) @Nullable String before,
            @RequestParam(required = false) @Nullable Integer size) {
        return journal.page(viewer, category, actor, from, to, before, size);
    }

    // No "produces": a refusal (429, 403) is answered in JSON, as everywhere else.
    @GetMapping("/api/v1/admin/audit/export")
    @Operation(summary = "The events of the same filters in CSV (text/csv, UTF-8, RFC 4180), written as they are read; "
            + "the export is recorded. 429 export_running while the same person exports already")
    void export(Viewer viewer, @RequestParam(required = false) @Nullable String category,
            @RequestParam(required = false) @Nullable String actor, @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to, HttpServletResponse response) throws IOException {
        journal.export(viewer, category, actor, from, to, response);
    }
}
