package com.dadacore.lore.identity.setup;

import com.dadacore.lore.identity.organization.OrganizationRepository;
import com.dadacore.lore.identity.organization.PublicUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Prints the address of Lore when it starts, and the setup code as long as no organization
 * exists.
 */
@Component
@ConditionalOnWebApplication
class SetupBanner {

    private static final Logger log = LoggerFactory.getLogger(SetupBanner.class);

    private final OrganizationRepository organizations;
    private final PublicUrl publicUrl;
    private final SetupCode setupCode;

    SetupBanner(OrganizationRepository organizations, PublicUrl publicUrl, SetupCode setupCode) {
        this.organizations = organizations;
        this.publicUrl = publicUrl;
        this.setupCode = setupCode;
    }

    @EventListener(ApplicationReadyEvent.class)
    void announce(ApplicationReadyEvent event) {
        String port = event.getApplicationContext().getEnvironment().getProperty("local.server.port", "8080");
        String address = publicUrl.fromEnvironment().orElse("http://localhost:" + port);
        String version = SetupBanner.class.getPackage().getImplementationVersion();
        log.info("Lore {} is ready at {}", version == null ? "(development)" : version, address);
        if (organizations.exists()) {
            return;
        }
        if (setupCode.fromEnvironment()) {
            log.info("Initial setup: open {}/setup and enter the code set in LORE_SETUP_CODE.", address);
            return;
        }
        log.info("""

                ==============================================================
                  Initial setup
                  Open {}/setup and enter this setup code:

                      {}

                  The code is printed again at each start until setup is done.
                ==============================================================
                """, address, setupCode.current());
    }
}
