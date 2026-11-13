package com.dadacore.lore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Lore: a technical knowledge workspace.
 *
 * <p>One application split into modules (identity, audit, web...), each in its own package. The
 * boundaries between modules are checked by Spring Modulith in {@code ModularityTests}.
 */
// Accounts are Lore's own (identity module): without this exclusion, Spring Security would
// create an unused in-memory user and print its "generated security password" in the logs.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class LoreApplication {

    public static void main(String[] args) {
        if (CommandLine.handles(args)) {
            System.exit(CommandLine.run(args));
        }
        SpringApplication.run(LoreApplication.class, args);
    }
}
