package com.dadacore.lore.search;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The results as the API sends them: plain texts, with the places of the words found in UTF-16
 * units; no HTML ever leaves the server.
 */
final class SearchViews {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern MARKS_OF_ACCENTS = Pattern.compile("\\p{M}+");

    private SearchViews() {
    }

    /** @param total the documents found with the filters */
    record SearchPage(List<Result> items, int page, int size, long total, SearchIndex.Facets facets) {
    }

    /**
     * @param version        the current version of the document, for the link of the passage
     * @param match          {@code passage}, {@code title} or {@code description}
     * @param passage        the best passage, for a match in the text; null otherwise
     * @param description    the description and its words found, for a match in it; null otherwise
     * @param words          the words found in the title and the excerpt, as written, for the reader
     * @param otherPassages  the other passages found in the document
     */
    record Result(ResultDocument document, int version, String match, @Nullable ResultPassage passage,
            @Nullable Excerpt description, List<String> words, OtherPassages otherPassages) {
    }

    /**
     * @param titleHighlights the words found in the title, as {@code [start, end[} in UTF-16 units
     * @param replacedBy      for an obsolete document, the one that replaces it, when the person may read it
     */
    record ResultDocument(UUID id, String title, List<List<Integer>> titleHighlights, String status,
            @Nullable String category, String mediaType, @Nullable String language, boolean restricted,
            String extractionStatus, Instant updatedAt, SearchIndex.Place space, SearchIndex.@Nullable Folder folder,
            SearchIndex.@Nullable DocumentRef replacedBy) {
    }

    /**
     * @param anchor     the anchor of the passage, for the link: {@code h-7-2}, {@code page-112}, {@code part-3}
     * @param headings   the titles of its sections, from the top
     * @param excerpt    one or two pieces of the passage around the words found, as plain text
     * @param highlights the words found in the excerpt, as {@code [start, end[} in UTF-16 units
     */
    record ResultPassage(String anchor, @Nullable Integer page, List<String> headings, String excerpt,
            List<List<Integer>> highlights) {
    }

    record Excerpt(String text, List<List<Integer>> highlights) {
    }

    /** @param total all the other passages found; items, the first five of them */
    record OtherPassages(int total, List<SearchIndex.PassageRef> items) {
    }

    /** @param titleHighlights the words of the title that begin with a word typed */
    record TitleResult(UUID id, String title, List<List<Integer>> titleHighlights, String status, String mediaType,
            SearchIndex.Place space, SearchIndex.@Nullable Folder folder) {
    }

    static SearchPage page(SearchIndex.Found found, int page, int size) {
        List<Result> items = new ArrayList<>(found.items().size());
        for (SearchIndex.FoundDocument document : found.items()) {
            Marks.Marked title = Marks.parse(document.markedTitle());
            ResultPassage passage = null;
            List<String> excerptWords = List.of();
            if (document.passage() != null) {
                Marks.Marked excerpt = Marks.parse(document.passage().markedExcerpt());
                passage = new ResultPassage(document.passage().anchor(), document.passage().page(),
                        document.passage().headings(), excerpt.text(), excerpt.highlights());
                excerptWords = excerpt.words();
            }
            Excerpt description = null;
            if (document.description() != null) {
                Marks.Marked marked = Marks.parse(document.description());
                description = new Excerpt(marked.text(), marked.highlights());
                excerptWords = marked.words();
            }
            items.add(new Result(
                    new ResultDocument(document.id(), title.text(), title.highlights(), document.status(),
                            document.category(), document.mediaType(), document.language(), document.restricted(),
                            document.extractionStatus(), document.updatedAt(), document.space(), document.folder(),
                            document.replacedBy()),
                    document.version(), document.match(), passage, description,
                    Searches.words(title.words(), excerptWords),
                    new OtherPassages(document.otherPassageCount(), document.otherPassages())));
        }
        return new SearchPage(List.copyOf(items), page, size, found.total(), found.facets());
    }

    static List<TitleResult> titles(List<SearchIndex.FoundTitle> found, String typed) {
        List<String> prefixes = new ArrayList<>();
        Matcher words = WORD.matcher(plain(typed));
        while (words.find()) {
            prefixes.add(words.group());
        }
        return found.stream()
                .map(title -> new TitleResult(title.id(), title.title(), beginnings(title.title(), prefixes),
                        title.status(), title.mediaType(), title.space(), title.folder()))
                .toList();
    }

    /** The words of a title that begin with a word typed, accents and capitals ignored. */
    static List<List<Integer>> beginnings(String title, List<String> prefixes) {
        List<List<Integer>> found = new ArrayList<>();
        Matcher word = WORD.matcher(title);
        while (word.find()) {
            String plainWord = plain(word.group());
            for (String prefix : prefixes) {
                if (plainWord.startsWith(prefix)) {
                    // The beginning, in the title as written: a letter with an accent may take two units.
                    int end = word.start();
                    int matched = 0;
                    while (end < word.end() && matched < prefix.length()) {
                        matched += plain(String.valueOf(title.charAt(end))).length();
                        end++;
                    }
                    found.add(List.of(word.start(), end));
                    break;
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * Without accents or capitals, as unaccent writes the title in the index: "Nœud" is found by
     * "noeud", and "noeud" begins "nœud".
     */
    private static String plain(String text) {
        return MARKS_OF_ACCENTS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("")
                .toLowerCase(Locale.ROOT).replace("œ", "oe").replace("æ", "ae").replace("ß", "ss");
    }
}
