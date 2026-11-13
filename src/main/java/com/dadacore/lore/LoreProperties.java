package com.dadacore.lore;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings read from the environment ({@code LORE_*} variables).
 *
 * @param publicUrl      address at which the team opens Lore ({@code LORE_PUBLIC_URL}).
 *                       When set, it takes precedence over the address entered at setup.
 * @param sessionTimeout maximum inactivity before a session expires
 *                       ({@code LORE_SESSION_TIMEOUT}). Administrators can only lower it.
 * @param setupCode      fixed initial setup code ({@code LORE_SETUP_CODE}), for automated
 *                       installations. When empty, a random code is printed in the logs.
 */
@ConfigurationProperties("lore")
public record LoreProperties(
        URI publicUrl,
        @DefaultValue("8h") Duration sessionTimeout,
        String setupCode) {
}
