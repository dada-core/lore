package com.dadacore.lore.identity.organization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.dadacore.lore.LoreProperties;
import com.dadacore.lore.audit.AuditEntry;
import com.dadacore.lore.audit.AuditLog;
import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.shared.ApiException;
import com.dadacore.lore.shared.Checks;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administration, General and Security: name, languages, time zone, sign-in rules.
 *
 * <p>Settings also set by the environment take its value: the public address is then read-only,
 * and the session timeout cannot exceed {@code LORE_SESSION_TIMEOUT}.
 */
@Service
class OrganizationSettingsService {

    private final OrganizationRepository organizations;
    private final PublicUrl publicUrl;
    private final LoreProperties properties;
    private final AuditLog audit;

    OrganizationSettingsService(OrganizationRepository organizations, PublicUrl publicUrl,
            LoreProperties properties, AuditLog audit) {
        this.organizations = organizations;
        this.publicUrl = publicUrl;
        this.properties = properties;
        this.audit = audit;
    }

    /**
     * @param publicUrl               address used in links (from the environment when
     *                                {@code publicUrlFromEnvironment} is true)
     * @param sessionTimeoutMaxMinutes ceiling set by {@code LORE_SESSION_TIMEOUT}
     */
    record SettingsView(
            String name,
            String slug,
            String defaultLocale,
            String timeZone,
            String publicUrl,
            boolean publicUrlFromEnvironment,
            int passwordMinLength,
            int loginMaxAttempts,
            int loginLockoutMinutes,
            int sessionTimeoutMinutes,
            int sessionTimeoutMaxMinutes,
            int invitationValidityHours) {
    }

    /** Fields left out (null) keep their value. */
    record SettingsUpdate(
            @Nullable String name,
            @Nullable String defaultLocale,
            @Nullable String timeZone,
            @Nullable String publicUrl,
            @Nullable Integer passwordMinLength,
            @Nullable Integer loginMaxAttempts,
            @Nullable Integer loginLockoutMinutes,
            @Nullable Integer sessionTimeoutMinutes,
            @Nullable Integer invitationValidityHours) {
    }

    SettingsView get(Viewer viewer, HttpServletRequest request) {
        viewer.requireAdmin();
        Organization organization = organizations.find(viewer.organizationId()).orElseThrow(ApiException::notFound);
        return view(organization, organizations.settings(organization.id()), request);
    }

    @Transactional
    SettingsView update(Viewer viewer, SettingsUpdate update, HttpServletRequest request) {
        viewer.requireAdmin();
        Organization organization = organizations.find(viewer.organizationId()).orElseThrow(ApiException::notFound);
        OrganizationSettings current = organizations.settings(organization.id());
        Checks checks = Checks.start();

        String name = update.name() == null ? organization.name() : checks.text("name", update.name(), 120);
        String locale = current.defaultLocale();
        if (update.defaultLocale() != null) {
            locale = update.defaultLocale().strip().toLowerCase(Locale.ROOT);
            if (!locale.equals("fr") && !locale.equals("en")) {
                checks.fail("defaultLocale", "invalid_value");
            }
        }
        String timeZone = update.timeZone() == null ? current.timeZone() : zone(checks, update.timeZone());
        String url = current.publicUrl();
        if (update.publicUrl() != null) {
            if (publicUrl.fromEnvironment().isPresent()) {
                checks.fail("publicUrl", "set_by_environment");
            } else if (update.publicUrl().isBlank()) {
                url = null;
            } else {
                url = PublicUrl.normalize(update.publicUrl());
                if (url == null) {
                    checks.fail("publicUrl", "invalid_url");
                }
            }
        }
        int sessionCeiling = sessionCeiling();
        int minLength = range(checks, "passwordMinLength", update.passwordMinLength(), current.passwordMinLength(), 8, 64);
        int attempts = range(checks, "loginMaxAttempts", update.loginMaxAttempts(), current.loginMaxAttempts(), 3, 20);
        int lockout = range(checks, "loginLockoutMinutes", update.loginLockoutMinutes(), current.loginLockoutMinutes(), 1, 24 * 60);
        int session = range(checks, "sessionTimeoutMinutes", update.sessionTimeoutMinutes(), current.sessionTimeoutMinutes(), 5, sessionCeiling);
        int invitation = range(checks, "invitationValidityHours", update.invitationValidityHours(), current.invitationValidityHours(), 1, 30 * 24);
        checks.orThrow();

        OrganizationSettings next = new OrganizationSettings(organization.id(), locale, timeZone, url,
                minLength, attempts, lockout, session, invitation);
        List<Map<String, Object>> changes = new ArrayList<>();
        change(changes, "name", organization.name(), name);
        change(changes, "defaultLocale", current.defaultLocale(), next.defaultLocale());
        change(changes, "timeZone", current.timeZone(), next.timeZone());
        change(changes, "publicUrl", current.publicUrl(), next.publicUrl());
        change(changes, "passwordMinLength", current.passwordMinLength(), next.passwordMinLength());
        change(changes, "loginMaxAttempts", current.loginMaxAttempts(), next.loginMaxAttempts());
        change(changes, "loginLockoutMinutes", current.loginLockoutMinutes(), next.loginLockoutMinutes());
        change(changes, "sessionTimeoutMinutes", current.sessionTimeoutMinutes(), next.sessionTimeoutMinutes());
        change(changes, "invitationValidityHours", current.invitationValidityHours(), next.invitationValidityHours());
        if (!changes.isEmpty()) {
            if (!name.equals(organization.name())) {
                organizations.rename(organization.id(), name);
            }
            organizations.saveSettings(next);
            audit.record(AuditEntry.of(organization.id(), viewer.userId(), "organization.settings_changed")
                    .resource("organization", organization.id())
                    .ip(viewer.ip())
                    .detail("changes", changes)
                    .build());
        }
        return view(organizations.find(organization.id()).orElseThrow(), next, request);
    }

    private SettingsView view(Organization organization, OrganizationSettings settings, HttpServletRequest request) {
        return new SettingsView(organization.name(), organization.slug(), settings.defaultLocale(),
                settings.timeZone(), publicUrl.resolve(settings, request), publicUrl.fromEnvironment().isPresent(),
                settings.passwordMinLength(), settings.loginMaxAttempts(), settings.loginLockoutMinutes(),
                Math.min(settings.sessionTimeoutMinutes(), sessionCeiling()), sessionCeiling(),
                settings.invitationValidityHours());
    }

    private int sessionCeiling() {
        return (int) Math.max(5, Math.min(Integer.MAX_VALUE, properties.sessionTimeout().toMinutes()));
    }

    private static String zone(Checks checks, String value) {
        try {
            return java.time.ZoneId.of(value.strip()).getId();
        } catch (java.time.DateTimeException e) {
            checks.fail("timeZone", "invalid_value");
            return null;
        }
    }

    private static int range(Checks checks, String field, Integer value, int current, int min, int max) {
        if (value == null) {
            return current;
        }
        if (value < min || value > max) {
            checks.fail(field, "out_of_range", Map.of("min", min, "max", max));
            return current;
        }
        return value;
    }

    private static void change(List<Map<String, Object>> changes, String setting, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("setting", setting);
            change.put("before", before);
            change.put("after", after);
            changes.add(change);
        }
    }
}
