package com.dadacore.lore.search;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dadacore.lore.identity.Viewer;

/**
 * Finds the documents a person may read. Every result, count and excerpt comes from the documents
 * the person may read, filtered in the query itself, before anything is ranked or counted.
 *
 * <p>Marked texts (titles, excerpts) are plain text in which each word found lies between
 * {@link #MARK_START} and {@link #MARK_END}: characters the extraction removes from every text.
 */
public interface SearchIndex {

    char MARK_START = '\u0002';
    char MARK_END = '\u0003';

    /**
     * A page of results. Field errors {@code q:common_words_only} and {@code q:exclusions_only};
     * {@code 503 search_timeout}.
     */
    Found search(Viewer viewer, Request request);

    /** The documents whose title holds a word beginning with each word typed, for the palette. */
    List<FoundTitle> titles(Viewer viewer, String text, int size);

    /**
     * @param text            what was typed, checked and prepared, without the words left out
     * @param terms           its terms: a word, a phrase, or words joined by {@code or}; a document
     *                        holds each of them, in its title, its description or any passage
     * @param excluded        the words and phrases left out ({@code -barman}): a document that holds
     *                        one of them, in its title, its description or a passage, is not found
     * @param spaces          only these spaces; empty for all
     * @param categories      only these categories; empty for all
     * @param statuses        only these statuses; empty for all
     * @param formats         only these media types; empty for all
     * @param updatedSince    only the documents updated since; null for any time
     * @param includeArchived the documents of the archived spaces the person sees, too
     */
    record Request(String text, List<String> terms, List<String> excluded, Set<UUID> spaces, Set<String> categories, Set<String> statuses, Set<String> formats,
            @Nullable Instant updatedSince, boolean includeArchived, int page, int size) {
    }

    /** @param total the documents found with the filters; the facets count them without */
    record Found(long total, List<FoundDocument> items, Facets facets) {
    }

    /**
     * @param match          {@code passage}, {@code title} or {@code description}
     * @param passage        the best passage, for a match in the text
     * @param description    the description, marked, for a match in the description
     * @param otherPassages  the other passages found, in the order of the document
     * @param replacedBy     for an obsolete document, the one that replaces it, when the person may read it
     */
    record FoundDocument(UUID id, String markedTitle, String status, @Nullable String category, String mediaType,
            @Nullable String language, boolean restricted, String extractionStatus, Instant updatedAt, Place space,
            @Nullable Folder folder, int version, String match, @Nullable FoundPassage passage,
            @Nullable String description, int otherPassageCount, List<PassageRef> otherPassages,
            @Nullable DocumentRef replacedBy) {
    }

    record DocumentRef(UUID id, String title) {
    }

    record FoundPassage(String anchor, @Nullable Integer page, List<String> headings, String markedExcerpt) {
    }

    record PassageRef(String anchor, @Nullable Integer page, List<String> headings) {
    }

    record Place(UUID id, String name, String slug, boolean archived) {
    }

    record Folder(UUID id, String name) {
    }

    /** Each value present in the results without filters, with its number of documents. */
    record Facets(List<SpaceCount> spaces, List<ValueCount> categories, List<ValueCount> statuses,
            List<ValueCount> formats) {
    }

    record SpaceCount(UUID id, String name, String slug, boolean archived, long count) {
    }

    record ValueCount(String value, long count) {
    }

    record FoundTitle(UUID id, String title, String status, String mediaType, Place space, @Nullable Folder folder) {
    }
}
