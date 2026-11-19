package com.dadacore.lore.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.dadacore.lore.identity.OrganizationRole;
import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.shared.ApiException;

/** What is typed, the marks of the excerpts, and the searches at once. */
class SearchesTests {

    private static final Viewer ELI = new Viewer(UUID.randomUUID(), UUID.randomUUID(), OrganizationRole.MEMBER,
            "Eli Brou", "eli.brou@dada.group", "fr", "session", "127.0.0.1");

    @Test
    void marksBecomePlacesInUtf16UnitsAndAnIdentifierIsOneWord() {
        char s = SearchIndex.MARK_START;
        char e = SearchIndex.MARK_END;
        Marks.Marked marked = Marks.parse("Si " + s + "pg" + e + "_" + s + "restore" + e + " signale " + s + "rôle" + e
                + " 🚀 " + s + "服务器" + e + " … " + s + "10.0.3.17" + e);

        assertThat(marked.text()).isEqualTo("Si pg_restore signale rôle 🚀 服务器 … 10.0.3.17");
        assertThat(marked.highlights()).containsExactly(List.of(3, 13), List.of(22, 26), List.of(30, 33), List.of(36, 45));
        // An emoji counts for two units, as in JavaScript.
        assertThat(marked.text().substring(30, 33)).isEqualTo("服务器");
        assertThat(marked.words()).containsExactly("pg_restore", "rôle", "服务器", "10.0.3.17");
    }

    @Test
    void thePaletteMarksTheBeginningsOfTheWordsOfATitleAsWritten() {
        // "noeud" begins "Nœud" (two units for one letter), "redem" begins "Redémarrage".
        assertThat(SearchViews.beginnings("Nœud Redémarrage du serveur", List.of("noeud", "redem")))
                .containsExactly(List.of(0, 4), List.of(5, 10));
        assertThat(SearchViews.beginnings("Nœud", List.of("no"))).containsExactly(List.of(0, 2));
        SearchIndex.Place space = new SearchIndex.Place(UUID.randomUUID(), "Infrastructure", "infrastructure", false);
        List<SearchViews.TitleResult> titles = SearchViews.titles(List.of(new SearchIndex.FoundTitle(UUID.randomUUID(),
                "Cœur du réseau", "PUBLISHED", "text/markdown", space, null)), "CŒ rés");
        assertThat(titles.getFirst().titleHighlights()).containsExactly(List.of(0, 2), List.of(8, 11));
    }

    @Test
    void anEmptyOrUnclosedMarkIsLeftOut() {
        char s = SearchIndex.MARK_START;
        char e = SearchIndex.MARK_END;
        Marks.Marked marked = Marks.parse("a" + s + e + "b " + s + "c");
        assertThat(marked.text()).isEqualTo("ab c");
        assertThat(marked.highlights()).isEmpty();
    }

    @Test
    void aWebAddressIsSearchedWithoutItsScheme() {
        assertThat(Searches.prepare("https://lore.example.com/docs/install")).isEqualTo("lore.example.com/docs/install");
        assertThat(Searches.prepare("voir HTTP://intranet/wiki et ssh://node-01")).isEqualTo("voir intranet/wiki et node-01");
        // A path of Windows, or an error code, keep their colon.
        assertThat(Searches.prepare("C:\\Windows\\System32")).isEqualTo("C:\\Windows\\System32");
        assertThat(Searches.prepare("x509: certificate has expired")).isEqualTo("x509: certificate has expired");
    }

    @Test
    void theWordsLeftOutAreSetApart() {
        assertThat(Searches.split("sauvegarde -barman -\"pg dump\" kube-system --no-owner"))
                .isEqualTo(new Searches.Split("sauvegarde kube-system --no-owner", List.of("barman", "\"pg dump\"")));
        assertThat(Searches.split("-barman")).isEqualTo(new Searches.Split("", List.of("barman")));
        assertThat(Searches.split("a - b")).isEqualTo(new Searches.Split("a - b", List.of()));
        // A command pasted between quotes keeps its options.
        assertThat(Searches.split("\"openssl x509 -enddate\" -noout")).isEqualTo(
                new Searches.Split("\"openssl x509 -enddate\"", List.of("noout")));
    }

    @Test
    void theWordsForTheReaderComeOnceEachTwentyAtMost() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add("mot" + i);
        }
        assertThat(Searches.words(List.of("Rôle", "serveur"), List.of("rôle", "Serveur", "base"))).containsExactly("Rôle", "serveur", "base");
        assertThat(Searches.words(many, List.of())).hasSize(20);
    }

    @Test
    void aPersonRunsTwoSearchesAtOnceAtMost() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        SearchIndex slow = new SearchIndex() {
            @Override
            public Found search(Viewer viewer, Request request) {
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new Found(0, List.of(), new Facets(List.of(), List.of(), List.of(), List.of()));
            }

            @Override
            public List<FoundTitle> titles(Viewer viewer, String text, int size) {
                return List.of();
            }
        };
        Searches searches = new Searches(slow, Clock.systemUTC());
        Searches.Asked asked = new Searches.Asked("sauvegarde", null, null, null, null, null, null, null, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> searches.search(ELI, asked));
            Future<?> second = pool.submit(() -> searches.search(ELI, asked));
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> searches.search(ELI, asked))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("too_many_searches"));
            // Someone else searches freely meanwhile.
            Viewer other = new Viewer(UUID.randomUUID(), ELI.organizationId(), OrganizationRole.MEMBER, "Hervé Coulibaly",
                    "herve.coulibaly@dada.group", "fr", "session-2", "127.0.0.1");
            assertThat(searches.titles(other, "rest", null)).isEmpty();

            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            // Once they end, the person searches again.
            assertThat(searches.search(ELI, asked).total()).isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void whatIsTypedIsChecked() {
        Searches searches = new Searches(new SearchIndex() {
            @Override
            public Found search(Viewer viewer, Request request) {
                throw new AssertionError("not asked");
            }

            @Override
            public List<FoundTitle> titles(Viewer viewer, String text, int size) {
                throw new AssertionError("not asked");
            }
        }, Clock.systemUTC());
        assertThat(codes(() -> searches.search(ELI, asked(" "))).toString()).contains("q=required");
        assertThat(codes(() -> searches.search(ELI, asked("x".repeat(501)))).toString()).contains("q=too_long");
        assertThat(codes(() -> searches.search(ELI, asked("a ".repeat(33)))).toString()).contains("q=too_many_words");
        assertThat(codes(() -> searches.search(ELI, new Searches.Asked("a", null, List.of("NOPE"), List.of("GONE"),
                List.of("image/png"), "1y", null, 50, 51))).toString())
                .contains("category=invalid_value", "status=invalid_value", "format=invalid_value", "updated=invalid_value",
                        "page=out_of_range", "size=out_of_range");
        assertThat(codes(() -> searches.titles(ELI, "x".repeat(101), 13)).toString()).contains("q=too_long", "size=out_of_range");
        // 1,000 documents at most: page 19 of 50, page 49 of 20.
        assertThat(codes(() -> searches.search(ELI, new Searches.Asked("a", null, null, null, null, null, null, 20, 50))).toString())
                .contains("page=out_of_range");
    }

    private static Searches.Asked asked(String q) {
        return new Searches.Asked(q, null, null, null, null, null, null, null, null);
    }

    private static List<String> codes(Runnable call) {
        try {
            call.run();
        } catch (ApiException e) {
            List<String> codes = new ArrayList<>();
            Object errors = e.properties().get("errors");
            if (errors instanceof List<?> list) {
                for (Object error : list) {
                    com.dadacore.lore.shared.FieldError field = (com.dadacore.lore.shared.FieldError) error;
                    codes.add(field.field() + "=" + field.code());
                }
            }
            return codes;
        }
        throw new AssertionError("no error");
    }
}
