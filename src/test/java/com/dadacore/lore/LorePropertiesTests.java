package com.dadacore.lore;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** The settings of the environment are checked when Lore starts: a wrong value stops it. */
class LorePropertiesTests {

    private final ApplicationContextRunner lore = new ApplicationContextRunner()
            .withUserConfiguration(Settings.class);

    @Test
    void aShortSetupCodeDoesNotStopTheStartOfAnInstallationSetUpAlready() {
        // The initial setup refuses it (SetupCodeTests): once set up, the code serves no more.
        for (String code : new String[] {"-", "1234", "ABCD-EFGH-JKLM", "", "  "}) {
            lore.withPropertyValues("lore.setup-code=" + code).run(context ->
                    assertThat(context).as(code).hasNotFailed());
        }
        lore.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void aSetupCodeIsComparedWithoutSpacesNorDashesInCapitals() {
        assertThat(LoreProperties.normalizeSetupCode(" ab-cd ef-gh 1 ")).isEqualTo("ABCDEFGH1");
        assertThat(LoreProperties.normalizeSetupCode(null)).isEmpty();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LoreProperties.class)
    static class Settings {
    }
}
