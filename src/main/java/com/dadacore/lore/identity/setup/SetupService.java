package com.dadacore.lore.identity.setup;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;

import com.dadacore.lore.audit.AuditEntry;
import com.dadacore.lore.audit.AuditLog;
import com.dadacore.lore.identity.OrganizationRole;
import com.dadacore.lore.identity.account.MembershipRepository;
import com.dadacore.lore.identity.account.UserAccount;
import com.dadacore.lore.identity.account.UserAccountRepository;
import com.dadacore.lore.identity.organization.Organization;
import com.dadacore.lore.identity.organization.OrganizationRepository;
import com.dadacore.lore.identity.organization.OrganizationSettings;
import com.dadacore.lore.identity.organization.PublicUrl;
import com.dadacore.lore.identity.organization.Slugs;
import com.dadacore.lore.identity.security.PasswordHasher;
import com.dadacore.lore.shared.ApiException;
import com.dadacore.lore.shared.Checks;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Initial setup: the organization and its first owner are created together, in one transaction.
 * An organization without an owner never exists.
 */
@Service
class SetupService {

    private final OrganizationRepository organizations;
    private final UserAccountRepository accounts;
    private final MembershipRepository memberships;
    private final PasswordHasher hasher;
    private final PublicUrl publicUrl;
    private final SetupCode setupCode;
    private final AuditLog audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    SetupService(OrganizationRepository organizations, UserAccountRepository accounts,
            MembershipRepository memberships, PasswordHasher hasher, PublicUrl publicUrl,
            SetupCode setupCode, AuditLog audit, JdbcClient jdbc, Clock clock) {
        this.organizations = organizations;
        this.accounts = accounts;
        this.memberships = memberships;
        this.hasher = hasher;
        this.publicUrl = publicUrl;
        this.setupCode = setupCode;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @param publicUrl the address set by {@code LORE_PUBLIC_URL}, if any */
    record SetupStatus(boolean required, @Nullable String publicUrl, boolean publicUrlFromEnvironment) {
    }

    /**
     * @param publicUrl ignored when {@code LORE_PUBLIC_URL} is set; otherwise links follow
     *                  the address of the request
     * @param locale    {@code fr} by default
     * @param timeZone  {@code UTC} by default
     */
    record SetupRequest(
            String setupCode,
            String organizationName,
            @Nullable String publicUrl,
            @Nullable String locale,
            @Nullable String timeZone,
            String displayName,
            String email,
            String password) {
    }

    record SetupResult(UUID userId, UUID organizationId, int sessionTimeoutMinutes) {
    }

    SetupStatus status() {
        return new SetupStatus(!organizations.exists(), publicUrl.fromEnvironment().orElse(null),
                publicUrl.fromEnvironment().isPresent());
    }

    /** Step 1 of the interface checks the code before asking for the administrator account. */
    void verifyCode(String code) {
        if (organizations.exists()) {
            throw ApiException.conflict("setup_done");
        }
        if (!setupCode.verify(code)) {
            Checks.start().fail("setupCode", "invalid_setup_code").orThrow();
        }
    }

    @Transactional
    SetupResult setup(SetupRequest request, String ip) {
        // Two people submitting at the same time: the first one wins.
        jdbc.sql("select pg_advisory_xact_lock(hashtext('lore.setup'))").query(Object.class).list();
        if (organizations.exists()) {
            throw ApiException.conflict("setup_done");
        }

        Checks checks = Checks.start();
        if (!setupCode.verify(request.setupCode())) {
            checks.fail("setupCode", "invalid_setup_code");
        }
        String name = checks.text("organizationName", request.organizationName(), 120);
        String locale = locale(checks, request.locale());
        String timeZone = timeZone(checks, request.timeZone());
        String url = null;
        if (publicUrl.fromEnvironment().isEmpty() && request.publicUrl() != null && !request.publicUrl().isBlank()) {
            url = PublicUrl.normalize(request.publicUrl());
            if (url == null) {
                checks.fail("publicUrl", "invalid_url");
            }
        }
        String displayName = checks.text("displayName", request.displayName(), 120);
        String email = checks.email("email", request.email());
        String password = checks.password("password", request.password(),
                OrganizationSettings.DEFAULT_PASSWORD_MIN_LENGTH);
        checks.orThrow();

        Organization organization = organizations.create(name, uniqueSlug(name));
        OrganizationSettings settings = OrganizationSettings.defaults(organization.id(), locale, timeZone, url);
        organizations.saveSettings(settings);
        UserAccount owner = accounts.create(email, displayName, hasher.hash(password), null);
        memberships.add(organization.id(), owner.id(), OrganizationRole.OWNER);
        // The owner is signed in right away: this is their first sign-in.
        accounts.recordLogin(owner.id(), clock.instant());
        audit.record(AuditEntry.of(organization.id(), owner.id(), "organization.created")
                .resource("organization", organization.id())
                .ip(ip)
                .detail("name", organization.name())
                .build());
        return new SetupResult(owner.id(), organization.id(), settings.sessionTimeoutMinutes());
    }

    private String uniqueSlug(String name) {
        String base = Slugs.of(name);
        String slug = base;
        for (int i = 2; organizations.slugTaken(slug); i++) {
            slug = base + "-" + i;
        }
        return slug;
    }

    static String locale(Checks checks, String value) {
        if (value == null || value.isBlank()) {
            return "fr";
        }
        String locale = value.strip().toLowerCase(java.util.Locale.ROOT);
        if (!locale.equals("fr") && !locale.equals("en")) {
            checks.fail("locale", "invalid_value");
            return null;
        }
        return locale;
    }

    static String timeZone(Checks checks, String value) {
        if (value == null || value.isBlank()) {
            return "UTC";
        }
        try {
            return ZoneId.of(value.strip()).getId();
        } catch (DateTimeException e) {
            checks.fail("timeZone", "invalid_value");
            return null;
        }
    }
}
