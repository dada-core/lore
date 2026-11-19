package com.dadacore.lore.identity.setup;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import com.dadacore.lore.LoreProperties;
import com.dadacore.lore.identity.organization.OrganizationRepository;
import com.dadacore.lore.identity.organization.PublicUrl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.util.unit.DataSize;

/** A fixed setup code too short to be safe stops the start of an installation still to set up, and only that. */
class SetupBannerTests {

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final PublicUrl publicUrl = mock(PublicUrl.class);

    private SetupBanner banner(String code) {
        when(publicUrl.fromEnvironment()).thenReturn(Optional.empty());
        return new SetupBanner(organizations, publicUrl, new SetupCode(new LoreProperties(null, Duration.ofHours(8), code,
                Path.of("files"), DataSize.ofGigabytes(1), 2, Duration.ofSeconds(120), DataSize.ofMegabytes(512))));
    }

    private static ApplicationReadyEvent ready() {
        ApplicationReadyEvent event = mock(ApplicationReadyEvent.class, RETURNS_DEEP_STUBS);
        when(event.getApplicationContext().getEnvironment().getProperty("local.server.port", "8080")).thenReturn("8080");
        return event;
    }

    @Test
    void aShortFixedCodeStopsTheStartWhileTheSetupIsNotDone() {
        when(organizations.exists()).thenReturn(false);
        assertThatThrownBy(() -> banner("1234").announce(ready()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LORE_SETUP_CODE");
    }

    @Test
    void anInstallationSetUpAlreadyStartsWhateverTheCode() {
        when(organizations.exists()).thenReturn(true);
        assertThatCode(() -> banner("1234").announce(ready())).doesNotThrowAnyException();
    }

    @Test
    void aCodeOfTwelveCharactersOrARandomOneLetsTheSetupStart() {
        when(organizations.exists()).thenReturn(false);
        assertThatCode(() -> banner("ABCD-EFGH-JKLM").announce(ready())).doesNotThrowAnyException();
        assertThatCode(() -> banner(null).announce(ready())).doesNotThrowAnyException();
    }
}
