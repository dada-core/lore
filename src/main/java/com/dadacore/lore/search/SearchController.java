package com.dadacore.lore.search;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dadacore.lore.identity.Viewer;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The search in the documents a person may read. Nothing of what is searched is written: neither
 * in the audit log nor in the logs of the server.
 */
@RestController
@Tag(name = "Search")
class SearchController {

    private final Searches searches;

    SearchController(Searches searches) {
        this.searches = searches;
    }

    @GetMapping("/api/v1/search")
    @Operation(summary = "The documents the person may read that match q (words, \"phrase\", -excluded, or), "
            + "ranked, obsolete ones last, with an excerpt of their best passage and the values of the filters "
            + "with their number of documents. Field errors on q: required, too_long (500), too_many_words (32), "
            + "common_words_only, exclusions_only. 429 too_many_searches, 503 search_timeout")
    SearchViews.SearchPage search(Viewer viewer, @RequestParam(required = false) @Nullable String q,
            @RequestParam(required = false) @Nullable List<UUID> space,
            @RequestParam(required = false) @Nullable List<String> category,
            @RequestParam(required = false) @Nullable List<String> status,
            @RequestParam(required = false) @Nullable List<String> format,
            @RequestParam(required = false) @Nullable String updated,
            @RequestParam(required = false) @Nullable Boolean includeArchived,
            @RequestParam(required = false) @Nullable Integer page,
            @RequestParam(required = false) @Nullable Integer size) {
        return searches.search(viewer, new Searches.Asked(q, space, category, status, format, updated,
                includeArchived, page, size));
    }

    @GetMapping("/api/v1/search/titles")
    @Operation(summary = "For the palette: the documents the person may read whose title has a word beginning "
            + "with each word typed, those being extracted included (size: 8 by default, 12 at most)")
    List<SearchViews.TitleResult> titles(Viewer viewer, @RequestParam(required = false) @Nullable String q,
            @RequestParam(required = false) @Nullable Integer size) {
        return searches.titles(viewer, q, size);
    }
}
