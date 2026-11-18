package com.dadacore.lore.library.documents;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The forms of the words: the stems of the analysis, widened, as PostgreSQL reads them back. */
class SearchIndexAdapterTests {

    @Test
    void stemsOfFiveLettersOrMoreAreShortenedAndTakenAsABeginning() {
        assertThat(SearchIndexAdapter.widened("'redemarrag' & 'serveur'")).isEqualTo("'redemarr':* & 'serve':*");
        assertThat(SearchIndexAdapter.widened("'verifi'")).isEqualTo("'verif':*");
        // Short stems stay exact: "port" does not find "portail".
        assertThat(SearchIndexAdapter.widened("'port' | 'rol'")).isEqualTo("'port' | 'rol'");
        // Phrases, exclusions and their operators are kept.
        assertThat(SearchIndexAdapter.widened("'pg' <-> 'restor' & !'barman'")).isEqualTo("'pg' <-> 'resto':* & !'barma':*");
        // A letter outside the ASCII range counts once; a quote in a lexeme stays escaped, and whole.
        assertThat(SearchIndexAdapter.widened("'écriture'")).isEqualTo("'écritu':*");
        assertThat(SearchIndexAdapter.widened("'l''écriture'")).isEqualTo("'l''écriture'");
        // A number, an address, a path: kept whole.
        assertThat(SearchIndexAdapter.widened("'10.0.3.18' & 'c:\\\\windows' & 'node-01'"))
                .isEqualTo("'10.0.3.18' & 'c:\\\\windows' & 'node-01'");
    }

    @Test
    void thePassagesFoundAreAskedWithoutPhrasesWhichTheIndexCannotCheck() {
        assertThat(SearchIndexAdapter.noPhrase("'pg' <-> 'resto':* | 'error':*")).isEqualTo("'pg' & 'resto':* | 'error':*");
        assertThat(SearchIndexAdapter.noPhrase("'base' <2> 'donne':*")).isEqualTo("'base' & 'donne':*");
        // What looks like an operator inside a lexeme is the lexeme.
        assertThat(SearchIndexAdapter.noPhrase("'a<->b' & 'l''<2>'")).isEqualTo("'a<->b' & 'l''<2>'");
        assertThat(SearchIndexAdapter.noPhrase("'c:\\\\x' <-> 'y'")).isEqualTo("'c:\\\\x' & 'y'");
    }
}
