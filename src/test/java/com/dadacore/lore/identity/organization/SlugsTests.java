package com.dadacore.lore.identity.organization;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The short address of an organization, derived from its name at setup. */
class SlugsTests {

    @Test
    void accentsAndCaseAreRemoved() {
        assertThat(Slugs.of("DadaGroup Réseaux")).isEqualTo("dadagroup-reseaux");
        assertThat(Slugs.of("Hervé & Fils, Côte Est")).isEqualTo("herve-fils-cote-est");
    }

    @Test
    void separatorsAreCollapsedAndTrimmed() {
        assertThat(Slugs.of("  --DadaGroup__2026--  ")).isEqualTo("dadagroup-2026");
    }

    @Test
    void aLongNameIsCutWithoutATrailingDash() {
        String slug = Slugs.of("a".repeat(59) + " suite");
        assertThat(slug).hasSizeLessThanOrEqualTo(60).doesNotEndWith("-");
    }

    @Test
    void aNameWithoutLettersOrDigitsStillGetsAnAddress() {
        assertThat(Slugs.of("¿?")).isEqualTo("organisation");
    }
}
