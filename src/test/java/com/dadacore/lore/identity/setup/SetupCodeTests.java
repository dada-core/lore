package com.dadacore.lore.identity.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;

import com.dadacore.lore.LoreProperties;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/** The code of the initial setup: a fixed one too short to be safe is refused, whatever is typed. */
class SetupCodeTests {

    private static SetupCode fixed(String code) {
        return new SetupCode(new LoreProperties(null, Duration.ofHours(8), code, Path.of("files"), DataSize.ofGigabytes(1),
                2, Duration.ofSeconds(120), DataSize.ofMegabytes(512)));
    }

    @Test
    void aFixedCodeOfLessThanTwelveCharactersIsRefusedEvenWhenTypedRight() {
        for (String code : new String[] {"-", "1234", " ab-cd ef-gh 1 "}) {
            SetupCode setup = fixed(code);
            assertThat(setup.tooShort()).as(code).isTrue();
            assertThat(setup.verify(code)).as(code).isFalse();
        }
    }

    @Test
    void aFixedCodeOfTwelveCharactersIsAcceptedWithoutSpacesNorDashesNorCase() {
        SetupCode setup = fixed("abcd-efgh-jklm");
        assertThat(setup.tooShort()).isFalse();
        assertThat(setup.verify("ABCD EFGH JKLM")).isTrue();
        assertThat(setup.verify("ABCD-EFGH-JKLN")).isFalse();
    }

    @Test
    void withoutAFixedCodeARandomOneOfTwelveCharactersIsUsed() {
        SetupCode setup = fixed(null);
        assertThat(setup.fromEnvironment()).isFalse();
        assertThat(setup.tooShort()).isFalse();
        assertThat(LoreProperties.normalizeSetupCode(setup.current())).hasSize(LoreProperties.SETUP_CODE_MIN_LENGTH);
        assertThat(setup.verify(setup.current())).isTrue();
    }
}
