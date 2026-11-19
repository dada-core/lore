package com.dadacore.lore.library.documents;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.dadacore.lore.access.DocumentAccess;
import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.search.SearchIndex;
import com.dadacore.lore.shared.ApiException;
import com.dadacore.lore.shared.Checks;
import com.dadacore.lore.shared.SqlCondition;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The search, written for PostgreSQL: the documents the person may read are chosen first, in the
 * query, and everything else (passages found, ranking, counts, filters and their counts, excerpts)
 * is computed from them only.
 *
 * <p>What is typed is analysed three times: in French and in English, with the stems of five letters
 * or more also taken as a beginning ("redemarrer" finds "Redémarrage"), against the vector of the
 * language of each passage; and as written, against its plain vector, which finds identifiers such
 * as {@code pg_restore} or {@code 10.0.3.17}. The exact forms rank higher than the widened ones.
 */
@Component
class SearchIndexAdapter implements SearchIndex {

    /** The marks of {@code ts_headline}, which the extraction removes from every text. */
    private static final String MARKS = "StartSel=" + MARK_START + ", StopSel=" + MARK_END;
    private static final String EXCERPT = MARKS + ", MaxWords=35, MinWords=15, MaxFragments=2, FragmentDelimiter=\" … \"";
    private static final String WHOLE = MARKS + ", HighlightAll=true";
    /**
     * The documents ranked by their passages: the first ones by an estimate that reads no vector
     * (the passages found, the title). The others follow, by that estimate: the same order on every
     * page, whatever the page asked.
     */
    private static final int RANKED = 200;
    /** Words as common as the stop words that the lists of PostgreSQL miss ("les" stems as "le"). */
    private static final java.util.Set<String> COMMON = java.util.Set.of("les");
    /** The other passages named under a result. */
    private static final int OTHER_PASSAGES = 5;
    private static final String QUERY_CANCELED = "57014";
    private static final java.util.regex.Pattern LETTERS = java.util.regex.Pattern.compile("\\p{L}+");
    /** The statuses shown when none is asked: all but Archived. */
    private static final String[] SHOWN_STATUSES = {"DRAFT", "PUBLISHED", "OBSOLETE"};

    private final JdbcClient jdbc;
    private final DocumentAccess access;
    private final DocumentRepository documents;
    private final TransactionTemplate transaction;
    private final JsonMapper json;
    private final Duration timeout;

    SearchIndexAdapter(JdbcClient jdbc, DocumentAccess access, DocumentRepository documents,
            PlatformTransactionManager transactions, JsonMapper json,
            @Value("${lore.search.statement-timeout:5s}") Duration timeout) {
        this.jdbc = jdbc;
        this.access = access;
        this.documents = documents;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setReadOnly(true);
        // Its own: the limits below never stay in the transaction of a caller (the assistant, later).
        this.transaction.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.json = json;
        this.timeout = timeout;
    }

    /** A term a document must hold: in its language, widened, and as written. */
    private record Term(String exact, String wide, String simple) {
    }

    /**
     * The analyses of what is typed, as PostgreSQL writes them: each term a document must hold,
     * then all of them joined by "or", which ranks a passage by the terms it gathers; and those of
     * the words left out, null when there is none.
     */
    private record Analysis(List<Term> terms, String exact, String wide, String simple,
            @Nullable String excludedLang, @Nullable String excludedSimple) {

        /** All the terms at once, in a form: what a title or a passage that holds them all matches. */
        String all(java.util.function.Function<Term, String> form) {
            return String.join(" & ", terms.stream().map(term -> "(" + form.apply(term) + ")").toList());
        }
    }

    @Override
    public Found search(Viewer viewer, Request request) {
        return inTime(() -> {
            Analysis analysis = analyse(request.text(), request.terms(), request.excluded());
            SqlCondition readable = access.readableDocuments(viewer, "d", "s");
            List<Object> params = new ArrayList<>();
            params.add(analysis.exact());
            params.add(analysis.wide());
            params.add(analysis.simple());
            params.add(analysis.all(Term::exact));
            params.add(analysis.all(Term::wide));
            params.add(analysis.all(Term::simple));
            // Written where the indexes are read: PostgreSQL uses an index for a value it knows
            // when it plans the statement, not for one that comes from another part of it.
            for (Term term : analysis.terms()) {
                params.add(term.wide());
                params.add(term.simple());
                params.add(term.wide());
                params.add(term.simple());
            }
            params.add(noPhrase(analysis.wide()));
            params.add(noPhrase(analysis.simple()));
            params.add(analysis.wide());
            params.add(analysis.simple());
            params.add(analysis.excludedLang());
            params.add(analysis.excludedSimple());
            params.add(viewer.organizationId());
            params.addAll(readable.params());
            params.add(analysis.excludedLang());
            params.add(analysis.excludedSimple());
            params.add(request.includeArchived());
            params.add(request.spaces().toArray(UUID[]::new));
            params.add(request.spaces().toArray(UUID[]::new));
            params.add(request.spaces().toArray(UUID[]::new));
            params.add(request.categories().toArray(String[]::new));
            params.add(request.categories().toArray(String[]::new));
            // Archived documents only when asked, even for who sees them.
            String[] statuses = request.statuses().isEmpty() ? SHOWN_STATUSES : request.statuses().toArray(String[]::new);
            boolean archivedAsked = request.statuses().contains("ARCHIVED");
            params.add(statuses);
            params.add(statuses);
            params.add(request.formats().toArray(String[]::new));
            params.add(request.formats().toArray(String[]::new));
            params.add(request.updatedSince() == null ? null : java.sql.Timestamp.from(request.updatedSince()));
            params.add(request.updatedSince() == null ? null : java.sql.Timestamp.from(request.updatedSince()));
            long offset = (long) request.page() * request.size();
            params.add(offset);
            params.add(offset + request.size());
            params.add(archivedAsked);
            params.add(request.includeArchived());
            params.add(archivedAsked);
            params.add(request.includeArchived());
            params.add(request.includeArchived());
            params.add(archivedAsked);
            String result = jdbc.sql(statement(analysis.terms().size()).replace(":readable", readable.sql()))
                    .params(params).query(String.class).single();
            return found(viewer, json.readTree(result));
        });
    }

    @Override
    public List<FoundTitle> titles(Viewer viewer, String text, int size) {
        return inTime(() -> {
            // Each word typed, as indexed: without accents or capitals, a beginning in the title (A).
            List<String> lexemes = jdbc.sql("""
                    select word from unnest(to_tsvector('lore_simple', ?)) w(word, positions, weights)
                    order by positions[1]
                    """)
                    .param(text)
                    .query(String.class)
                    .list();
            if (lexemes.isEmpty()) {
                return List.of();
            }
            String prefixes = String.join(" & ", lexemes.stream().map(word -> quoted(word) + ":*A").toList());
            SqlCondition readable = access.readableDocuments(viewer, "d", "s");
            List<Object> params = new ArrayList<>();
            params.add(prefixes);
            params.add(viewer.organizationId());
            params.addAll(readable.params());
            params.add(text);
            params.add(size);
            return jdbc.sql("""
                    select d.id, d.title, d.status, v.media_type, s.id as space_id, s.name as space_name, s.slug,
                        f.id as folder_id, f.name as folder_name
                    from document d
                    join space s on s.id = d.space_id
                    join document_version v on v.id = d.current_version_id
                    left join folder f on f.id = d.folder_id
                    where d.tsv_simple @@ ?::tsquery and s.organization_id = ? and s.archived_at is null
                      and d.status <> 'ARCHIVED' and (:readable)
                    order by starts_with(lower(unaccent(d.title)), lower(unaccent(?))) desc, d.updated_at desc, d.id
                    limit ?
                    """.replace(":readable", readable.sql()))
                    .params(params)
                    .query((rs, row) -> new FoundTitle(rs.getObject("id", UUID.class), rs.getString("title"),
                            rs.getString("status"), rs.getString("media_type"),
                            new Place(rs.getObject("space_id", UUID.class), rs.getString("space_name"),
                                    rs.getString("slug"), false),
                            rs.getObject("folder_id", UUID.class) == null ? null
                                    : new Folder(rs.getObject("folder_id", UUID.class), rs.getString("folder_name"))))
                    .list();
        });
    }

    /**
     * Field errors {@code q:exclusions_only} (only words to leave out, which would find everything
     * else) and {@code q:common_words_only} (nothing but words such as "le" or "the", found everywhere).
     */
    private Analysis analyse(String text, List<String> typed, List<String> excluded) {
        record Trees(String frTree, String enTree, String simpleTree) {
        }
        Trees whole = jdbc.sql("""
                select querytree(websearch_to_tsquery('lore_fr', ?)), querytree(websearch_to_tsquery('lore_en', ?)),
                    querytree(websearch_to_tsquery('lore_simple', ?))
                """)
                .params(text, text, text)
                .query((rs, row) -> new Trees(rs.getString(1), rs.getString(2), rs.getString(3)))
                .single();
        if (empty(whole.simpleTree())) {
            boolean exclusions = "T".equals(whole.simpleTree());
            throw Checks.rejected("q", exclusions ? "exclusions_only" : "common_words_only");
        }
        if (empty(whole.frTree()) && empty(whole.enTree())) {
            throw Checks.rejected("q", "common_words_only");
        }
        // Each term in French and in English, and as written. A stop word is not required ("le",
        // "the", "des"), unless it is typed in capitals while a stop word in one language only: SES,
        // IT or AS are names in operations.
        record Analysed(String exact, String simple, String frTree, String enTree, String simpleTree) {
        }
        List<Analysed> analysed = jdbc.sql("""
                select (fr || en)::text, simple::text, querytree(fr), querytree(en), querytree(simple)
                from unnest(?::text[]) with ordinality t(term, ord)
                cross join lateral (select websearch_to_tsquery('lore_fr', t.term) as fr,
                    websearch_to_tsquery('lore_en', t.term) as en, websearch_to_tsquery('lore_simple', t.term) as simple) q
                order by t.ord
                """)
                .param(typed.toArray(String[]::new))
                .query((rs, row) -> new Analysed(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)))
                .list();
        List<Term> terms = new ArrayList<>();
        List<String> exact = new ArrayList<>();
        List<String> simple = new ArrayList<>();
        for (int i = 0; i < analysed.size(); i++) {
            Analysed term = analysed.get(i);
            String written = typed.get(i);
            boolean name = written.equals(written.toUpperCase(java.util.Locale.ROOT)) && written.chars().anyMatch(Character::isLetter);
            boolean stop = (empty(term.frTree()) && empty(term.enTree())) || ((empty(term.frTree()) || empty(term.enTree())) && !name);
            if (stop || empty(term.simpleTree()) || COMMON.contains(written.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            terms.add(new Term(term.exact(), widened(term.exact()), term.simple()));
            exact.add("(" + term.exact() + ")");
            simple.add("(" + term.simple() + ")");
        }
        if (terms.isEmpty()) {
            throw Checks.rejected("q", "common_words_only");
        }
        String any = String.join(" | ", exact);
        String anySimple = String.join(" | ", simple);
        if (excluded.isEmpty()) {
            return new Analysis(List.copyOf(terms), any, widened(any), anySimple, null, null);
        }
        // One of the words left out, in any of the analyses, is enough to leave a document out. They
        // are read as words or phrases, never as the syntax of a search (-(-bar) is no double
        // negation); a single letter or a stop word is not left out (-n in a pasted command would
        // leave out every document that holds "n'est").
        List<String> out = jdbc.sql("""
                select coalesce(string_agg('(' || (fr || en)::text || ')', ' | '), ''),
                    coalesce(string_agg('(' || simple::text || ')', ' | '), '')
                from unnest(?::text[]) t(term)
                cross join lateral (select btrim(t.term, '"') as words, t.term like '"%' as phrase) w
                cross join lateral (select
                    case when w.phrase then phraseto_tsquery('lore_fr', w.words) else plainto_tsquery('lore_fr', w.words) end as fr,
                    case when w.phrase then phraseto_tsquery('lore_en', w.words) else plainto_tsquery('lore_en', w.words) end as en,
                    case when w.phrase then phraseto_tsquery('lore_simple', w.words) else plainto_tsquery('lore_simple', w.words) end as simple
                ) q
                where char_length(w.words) > 1 and querytree(q.fr) not in ('', 'T') and querytree(q.en) not in ('', 'T')
                  and querytree(q.simple) not in ('', 'T')
                """)
                .param(excluded.toArray(String[]::new))
                .query((rs, row) -> List.of(rs.getString(1), rs.getString(2)))
                .single();
        return new Analysis(List.copyOf(terms), any, widened(any), anySimple,
                out.get(0).isBlank() ? null : out.get(0), out.get(1).isBlank() ? null : out.get(1));
    }

    /** No lexeme to look for: nothing but stop words, or nothing but exclusions ({@code T}). */
    private static boolean empty(@Nullable String tree) {
        return tree == null || tree.isBlank() || "T".equals(tree);
    }

    /**
     * The stems of five letters or more, shortened by two letters (five at least) and taken as a
     * beginning: the French stemmer leaves "redemarr" and "redemarrag" apart, and "verifi" and "verif".
     * Words only: numbers, addresses, versions and identifiers keep their exact form.
     */
    static String widened(String query) {
        StringBuilder out = new StringBuilder(query.length() + 16);
        int i = 0;
        while (i < query.length()) {
            char c = query.charAt(i);
            if (c != '\'') {
                out.append(c);
                i++;
                continue;
            }
            StringBuilder lexeme = new StringBuilder();
            i++;
            while (i < query.length()) {
                char d = query.charAt(i);
                if (d == '\\' && i + 1 < query.length()) {
                    lexeme.append(query.charAt(i + 1));
                    i += 2;
                } else if (d == '\'' && i + 1 < query.length() && query.charAt(i + 1) == '\'') {
                    lexeme.append('\'');
                    i += 2;
                } else if (d == '\'') {
                    i++;
                    break;
                } else {
                    lexeme.append(d);
                    i++;
                }
            }
            String word = lexeme.toString();
            int length = word.codePointCount(0, word.length());
            // Words only: a number, an address or a version stays whole (10.0.3.18 is not 10.0.3.17).
            if (length >= 5 && LETTERS.matcher(word).matches()) {
                int keep = Math.max(5, length - 2);
                out.append(quoted(word.substring(0, word.offsetByCodePoints(0, keep)))).append(":*");
            } else {
                out.append(quoted(word));
            }
        }
        return out.toString();
    }

    /**
     * The phrase operators of a query ({@code <->}, {@code <2>}) written as AND, outside its quoted
     * lexemes: what the index answers alone, without reading a vector again. An identifier is a phrase
     * for PostgreSQL ({@code pg_restore} is {@code 'pg' <-> 'restore'}): the passages found that way
     * hold all its words, and those of the phrase rank higher anyway.
     */
    static String noPhrase(String query) {
        StringBuilder out = new StringBuilder(query.length());
        boolean quoted = false;
        int i = 0;
        while (i < query.length()) {
            char c = query.charAt(i);
            if (quoted) {
                out.append(c);
                if (c == '\\' && i + 1 < query.length()) {
                    out.append(query.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '\'') {
                    if (i + 1 < query.length() && query.charAt(i + 1) == '\'') {
                        out.append('\'');
                        i += 2;
                        continue;
                    }
                    quoted = false;
                }
                i++;
                continue;
            }
            if (c == '\'') {
                quoted = true;
                out.append(c);
                i++;
                continue;
            }
            if (c == '<') {
                int end = query.indexOf('>', i);
                if (end > i && query.substring(i + 1, end).matches("-|\\d+")) {
                    out.append('&');
                    i = end + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** A lexeme quoted for a {@code tsquery}. */
    private static String quoted(String lexeme) {
        return "'" + lexeme.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    /** The search has five seconds: {@code 503 search_timeout} beyond, in its own read-only transaction. */
    private <T> T inTime(Supplier<T> search) {
        try {
            return transaction.execute(status -> {
                jdbc.sql("select set_config('statement_timeout', ?, true)").param(timeout.toMillis() + "ms")
                        .query(String.class).single();
                // The table of the passages is small, its vectors stored apart: PostgreSQL would read
                // all of them rather than ask the indexes, not counting what reading a vector costs.
                jdbc.sql("select set_config('enable_seqscan', 'off', true)").query(String.class).single();
                // A plan for the words of this search: a plan made for any words would not know which
                // index to read (the driver prepares a statement run five times).
                jdbc.sql("select set_config('plan_cache_mode', 'force_custom_plan', true)").query(String.class).single();
                return search.get();
            });
        } catch (DataAccessException e) {
            if (e.getMostSpecificCause() instanceof SQLException sql && QUERY_CANCELED.equals(sql.getSQLState())) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "search_timeout");
            }
            throw e;
        }
    }

    private Found found(Viewer viewer, JsonNode result) {
        // The documents that replace obsolete ones, when the person may read them: no title otherwise.
        java.util.Set<UUID> replacing = new java.util.HashSet<>();
        for (JsonNode item : result.path("items")) {
            JsonNode replacedBy = item.path("replaced_by_id");
            if (!replacedBy.isNull() && !replacedBy.isMissingNode()) {
                replacing.add(UUID.fromString(replacedBy.asString()));
            }
        }
        java.util.Map<UUID, DocumentRef> readable = new java.util.HashMap<>();
        documents.findAll(viewer.organizationId(), replacing, access.readableDocuments(viewer, "d", "s"))
                .forEach(row -> readable.put(row.id(), new DocumentRef(row.id(), row.title())));
        List<FoundDocument> items = new ArrayList<>();
        for (JsonNode item : result.path("items")) {
            List<PassageRef> others = new ArrayList<>();
            for (JsonNode other : item.path("others")) {
                others.add(new PassageRef(other.path("anchor").asString(), integer(other.path("page")),
                        strings(other.path("headings"))));
            }
            FoundPassage passage = item.path("anchor").isNull() || item.path("anchor").isMissingNode() ? null
                    : new FoundPassage(item.path("anchor").asString(), integer(item.path("page")),
                            strings(item.path("headings")), item.path("excerpt").asString(""));
            JsonNode folder = item.path("folder_id");
            items.add(new FoundDocument(
                    UUID.fromString(item.path("id").asString()),
                    item.path("title").asString(),
                    item.path("status").asString(),
                    text(item.path("category")),
                    item.path("media_type").asString(),
                    text(item.path("language")),
                    item.path("restricted").asBoolean(),
                    item.path("extraction_status").asString(),
                    Instant.ofEpochMilli(item.path("updated_ms").asLong()),
                    new Place(UUID.fromString(item.path("space_id").asString()), item.path("space_name").asString(),
                            item.path("space_slug").asString(), item.path("archived").asBoolean()),
                    folder.isNull() || folder.isMissingNode() ? null
                            : new Folder(UUID.fromString(folder.asString()), item.path("folder_name").asString()),
                    item.path("number").asInt(),
                    item.path("match").asString(),
                    passage,
                    text(item.path("description")),
                    Math.max(0, item.path("passage_count").asInt() - 1),
                    List.copyOf(others),
                    item.path("replaced_by_id").isNull() || item.path("replaced_by_id").isMissingNode() ? null
                            : readable.get(UUID.fromString(item.path("replaced_by_id").asString()))));
        }
        JsonNode facets = result.path("facets");
        List<SpaceCount> spaces = new ArrayList<>();
        for (JsonNode space : facets.path("spaces")) {
            spaces.add(new SpaceCount(UUID.fromString(space.path("id").asString()), space.path("name").asString(),
                    space.path("slug").asString(), space.path("archived").asBoolean(), space.path("count").asLong()));
        }
        return new Found(result.path("total").asLong(), List.copyOf(items), new Facets(List.copyOf(spaces),
                values(facets.path("categories")), values(facets.path("statuses")), values(facets.path("formats"))));
    }

    private static List<ValueCount> values(JsonNode node) {
        List<ValueCount> values = new ArrayList<>();
        for (JsonNode value : node) {
            values.add(new ValueCount(value.path("value").asString(), value.path("count").asLong()));
        }
        return List.copyOf(values);
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            values.add(value.asString());
        }
        return List.copyOf(values);
    }

    private static @Nullable String text(JsonNode node) {
        return node.isNull() || node.isMissingNode() ? null : node.asString();
    }

    private static @Nullable Integer integer(JsonNode node) {
        return node.isNull() || node.isMissingNode() ? null : node.asInt();
    }

    /**
     * One statement, one row: the total, the page and the facets, in JSON. Parameters, in order:
     * the analyses of the whole; for each term, its two forms twice (passages, then titles and
     * descriptions); the whole again twice; the words left out; the organization, the condition
     * "documents the person may read" and the words left out again; then each filter twice (empty:
     * all), the size and the offset, and the archived spaces asked, for the counts.
     */
    private static String statement(int terms) {
        StringBuilder sql = new StringBuilder("""
                with query as (
                    select ?::tsquery as exact, ?::tsquery as wide, ?::tsquery as simple,
                        ?::tsquery as exact_all, ?::tsquery as wide_all, ?::tsquery as simple_all
                ),
                """);
        // The versions that hold each term somewhere: a passage, the title or the description.
        for (int i = 1; i <= terms; i++) {
            sql.append("""
                    term_%1$d as materialized (
                        select c.version_id from chunk c where c.tsv_lang @@ ?::tsquery or c.tsv_simple @@ ?::tsquery
                        union
                        select d.current_version_id from document d
                        where d.current_version_id is not null and (d.tsv_lang @@ ?::tsquery or d.tsv_simple @@ ?::tsquery)
                    ),
                    """.formatted(i));
        }
        sql.append("candidates as materialized (\n");
        for (int i = 1; i <= terms; i++) {
            sql.append(i == 1 ? "    " : "    intersect ").append("select version_id from term_").append(i).append('\n');
        }
        sql.append("),\n");
        return sql.append(MATCHING).append(TAIL).toString();
    }

    /** The passages, titles and descriptions of the candidates that hold some of the terms, ranked. */
    private static final String MATCHING = """
            -- The passages of the candidates that hold some of the terms, through the indexes alone: no
            -- vector is read (phrases written as AND), then kept for the candidates only.
            hits as materialized (
                select h.version_id, h.id from (
                    select c.version_id, c.id from chunk c
                    where c.tsv_lang @@ ?::tsquery or c.tsv_simple @@ ?::tsquery
                    offset 0
                ) h
                where h.version_id in (select version_id from candidates)
            ),
            -- The documents whose title or description holds some of the terms, through their indexes.
            titled as materialized (
                select d.id,
                    greatest(ts_rank_cd(d.tsv_lang, query.wide, 32), ts_rank_cd(d.tsv_simple, query.simple, 32)) as rank,
                    ts_filter(d.tsv_lang, '{a}') @@ query.wide or ts_filter(d.tsv_simple, '{a}') @@ query.simple as in_title,
                    ts_filter(d.tsv_lang, '{a}') @@ query.wide_all or ts_filter(d.tsv_simple, '{a}') @@ query.simple_all as whole_title
                from document d cross join query
                where (d.tsv_lang @@ ?::tsquery or d.tsv_simple @@ ?::tsquery)
                  and d.current_version_id in (select version_id from candidates)
            ),
            -- The versions that hold a word left out, in any analysis; none without exclusion.
            excluded as materialized (
                select distinct c.version_id from chunk c
                where c.tsv_lang @@ ?::tsquery or c.tsv_simple @@ ?::tsquery
            ),
            -- The documents the person may read, before anything is ranked or counted. A version being
            -- extracted for the first time is not found yet; a failed one by its title and description
            -- only, having no passage; a scanned one by them and the passages of its pages of text. A
            -- word left out, anywhere, leaves a document out.
            readable as (
                select d.id, d.space_id, d.folder_id, d.title, d.description, d.category, d.status, d.restricted,
                    d.updated_at, d.search_language, d.current_version_id, d.replaced_by_id,
                    v.number, v.media_type, v.language, v.extraction_status,
                    s.name as space_name, s.slug as space_slug, s.archived_at is not null as archived
                from document d
                join space s on s.id = d.space_id
                join document_version v on v.id = d.current_version_id
                where s.organization_id = ? and v.extraction_status <> 'PENDING' and (:readable)
                  and d.current_version_id in (select version_id from candidates)
                  and not exists (select 1 from excluded e where e.version_id = d.current_version_id)
                  and not coalesce(d.tsv_lang @@ ?::tsquery, false)
                  and not coalesce(d.tsv_simple @@ ?::tsquery, false)
            ),
            -- An estimate that reads no vector: the title, then the passages found.
            found as (
                select r.*, coalesce(h.n, 0) as passage_count, t.rank as title_rank, coalesce(t.in_title, false) as in_title,
                    coalesce(t.whole_title, false) as whole_title,
                    2 * coalesce(t.rank, 0) + case when t.whole_title then 1 else 0 end + 0.1 * ln(1 + coalesce(h.n, 0)) as estimate
                from readable r
                left join (select version_id, count(*) as n from hits group by version_id) h
                    on h.version_id = r.current_version_id
                left join titled t on t.id = r.id
            ),
            """;

    /**
     * A passage ranks by the terms it gathers, the exact forms above the widened ones, its rank
     * bounded; one that holds them all ranks above, higher still with their exact forms.
     */
    private static final String PASSAGE_RANK = """
            greatest(ts_rank_cd(c.tsv_lang, query.exact, 32) + 0.5 * ts_rank_cd(c.tsv_lang, query.wide, 32),
                     ts_rank_cd(c.tsv_simple, query.simple, 32))
                + case when c.tsv_lang @@ query.exact_all or c.tsv_simple @@ query.simple_all then 0.5
                       when c.tsv_lang @@ query.wide_all then 0.25 else 0 end""";

    private static final String TAIL = """
            -- The filters. The documents of an archived space only when asked, or when its space is.
            shown as (
                select * from found
                where (not archived or ? or space_id = any(?))
                  and (cardinality(?::uuid[]) = 0 or space_id = any(?::uuid[]))
                  and (cardinality(?::text[]) = 0 or category = any(?::text[]))
                  and (cardinality(?::text[]) = 0 or status = any(?::text[]))
                  and (cardinality(?::text[]) = 0 or media_type = any(?::text[]))
                  and (?::timestamptz is null or updated_at >= ?::timestamptz)
            ),
            -- The documents ranked by their passages: always the first %4$d by the estimate.
            top as materialized (
                select current_version_id from shown
                order by status = 'OBSOLETE', estimate desc, updated_at desc, id limit %4$d
            ),
            top_passages as materialized (
                select c.version_id, c.id, c.ordinal, %5$s as rank
                from chunk c cross join query
                where c.id in (select h.id from hits h where h.version_id in (select current_version_id from top))
            ),
            top_best as (
                select distinct on (version_id) version_id, rank from top_passages
                order by version_id, rank desc, ordinal
            ),
            -- Obsolete documents last, whatever their rank; the ranked ones first; then the estimate,
            -- the most recently updated, and a stable order.
            ordered as (
                select s.*, s.current_version_id in (select current_version_id from top) as ranked,
                    coalesce(b.rank, 0) + 2 * coalesce(s.title_rank, 0) + case when s.whole_title then 1 else 0 end as rank
                from shown s
                left join top_best b on b.version_id = s.current_version_id
            ),
            page as materialized (
                select * from (
                    select *, row_number() over (order by status = 'OBSOLETE', ranked desc, rank desc, estimate desc,
                        updated_at desc, id) as position
                    from ordered
                ) o
                where position > ? and position <= ?
            ),
            -- The passages of the page: those already ranked, and those of a page beyond the first ones.
            passages as (
                select * from top_passages where version_id in (select current_version_id from page)
                union all
                select c.version_id, c.id, c.ordinal, %5$s as rank
                from chunk c cross join query
                where c.id in (select h.id from hits h where h.version_id in (select current_version_id from page)
                                and h.version_id not in (select current_version_id from top))
            ),
            best as (
                select distinct on (version_id) version_id, id, rank from passages
                order by version_id, rank desc, ordinal
            )
            select json_build_object(
                'total', (select count(*) from shown),
                'items', coalesce((
                    select json_agg(json_build_object(
                        'id', p.id,
                        'title', case when strpos(t.marked, chr(2)) > 0 then t.marked
                                 else ts_headline('lore_simple', p.title, q.simple, '%2$s') end,
                        'status', p.status, 'category', p.category, 'media_type', p.media_type, 'language', p.language,
                        'restricted', p.restricted, 'extraction_status', p.extraction_status,
                        'replaced_by_id', case when p.status = 'OBSOLETE' then p.replaced_by_id end,
                        'updated_ms', (extract(epoch from p.updated_at) * 1000)::bigint,
                        'space_id', p.space_id, 'space_name', p.space_name, 'space_slug', p.space_slug,
                        'archived', p.archived, 'folder_id', p.folder_id, 'folder_name', f.name,
                        'number', p.number, 'passage_count', p.passage_count,
                        'match', case when p.best_chunk is not null then 'passage' when p.in_title then 'title'
                                 else 'description' end,
                        'anchor', c.anchor, 'page', c.page, 'headings', c.headings,
                        'excerpt', case when e.marked is null then null
                            when strpos(e.marked, chr(2)) > 0 then e.marked
                            else ts_headline('lore_simple', c.content, q.simple, '%1$s') end,
                        'description', case when p.best_chunk is not null or p.in_title then null
                            when strpos(ts_headline(lore_config(p.search_language), p.description, q.wide, '%1$s'), chr(2)) > 0
                                then ts_headline(lore_config(p.search_language), p.description, q.wide, '%1$s')
                            else ts_headline('lore_simple', p.description, q.simple, '%1$s') end,
                        'others', (select coalesce(json_agg(json_build_object('anchor', o.anchor, 'page', o.page,
                                'headings', o.headings) order by o.ordinal), '[]'::json)
                            from (select oc.anchor, oc.page, oc.headings, oc.ordinal
                                  from passages op join chunk oc on oc.id = op.id
                                  where op.version_id = p.current_version_id and op.id is distinct from p.best_chunk
                                  order by op.ordinal limit %3$d) o)
                    ) order by p.position)
                    from (select pg.*, b.id as best_chunk from page pg left join best b on b.version_id = pg.current_version_id) p
                    cross join query q
                    left join folder f on f.id = p.folder_id
                    left join chunk c on c.id = p.best_chunk
                    -- In the language of the document first; as written when that marks nothing.
                    cross join lateral (select ts_headline(lore_config(p.search_language), p.title, q.wide, '%2$s') as marked) t
                    left join lateral (select ts_headline(lore_config(c.language), c.content, q.wide, '%1$s') as marked
                                       where c.id is not null) e on true
                ), '[]'::json),
                'facets', json_build_object(
                    'spaces', coalesce((select json_agg(json_build_object('id', x.space_id, 'name', x.space_name,
                            'slug', x.space_slug, 'archived', x.archived, 'count', x.n) order by x.archived, x.n desc, x.space_name)
                        from (select space_id, space_name, space_slug, archived, count(*) as n from found
                              where status <> 'ARCHIVED' or ? group by space_id, space_name, space_slug, archived) x), '[]'::json),
                    'categories', coalesce((select json_agg(json_build_object('value', x.category, 'count', x.n) order by x.n desc, x.category)
                        from (select category, count(*) as n from found where category is not null
                              and (not archived or ?) and (status <> 'ARCHIVED' or ?) group by category) x), '[]'::json),
                    'statuses', coalesce((select json_agg(json_build_object('value', x.status, 'count', x.n) order by x.n desc, x.status)
                        from (select status, count(*) as n from found where not archived or ? group by status) x), '[]'::json),
                    'formats', coalesce((select json_agg(json_build_object('value', x.media_type, 'count', x.n) order by x.n desc, x.media_type)
                        from (select media_type, count(*) as n from found where (not archived or ?)
                              and (status <> 'ARCHIVED' or ?) group by media_type) x), '[]'::json)
                )
            )::text
            """.formatted(EXCERPT, WHOLE, OTHER_PASSAGES, RANKED, PASSAGE_RANK);
}
