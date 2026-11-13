package com.dadacore.lore.identity.organization;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrganizationRepository {

    private final JdbcClient jdbc;

    public OrganizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The organization of this installation. Version 0.1 has one per installation, created at
     * setup; the data model already holds several, for the hosted offer.
     */
    public Optional<Organization> installation() {
        return jdbc.sql("select id, name, slug, created_at from organization order by created_at, id limit 1")
                .query(OrganizationRepository::organization)
                .optional();
    }

    public boolean exists() {
        return jdbc.sql("select exists (select 1 from organization)").query(Boolean.class).single();
    }

    public Optional<Organization> find(UUID id) {
        return jdbc.sql("select id, name, slug, created_at from organization where id = ?")
                .param(id)
                .query(OrganizationRepository::organization)
                .optional();
    }

    public Organization create(String name, String slug) {
        return jdbc.sql("insert into organization (name, slug) values (?, ?) returning id, name, slug, created_at")
                .params(name, slug)
                .query(OrganizationRepository::organization)
                .single();
    }

    public void rename(UUID id, String name) {
        jdbc.sql("update organization set name = ? where id = ?").params(name, id).update();
    }

    public OrganizationSettings settings(UUID organizationId) {
        return jdbc.sql("""
                select organization_id, default_locale, time_zone, public_url, password_min_length,
                       login_max_attempts, login_lockout_minutes, session_timeout_minutes,
                       invitation_validity_hours
                from organization_settings where organization_id = ?
                """)
                .param(organizationId)
                .query((rs, row) -> new OrganizationSettings(
                        rs.getObject("organization_id", UUID.class),
                        rs.getString("default_locale"),
                        rs.getString("time_zone"),
                        rs.getString("public_url"),
                        rs.getInt("password_min_length"),
                        rs.getInt("login_max_attempts"),
                        rs.getInt("login_lockout_minutes"),
                        rs.getInt("session_timeout_minutes"),
                        rs.getInt("invitation_validity_hours")))
                .single();
    }

    public void saveSettings(OrganizationSettings s) {
        jdbc.sql("""
                insert into organization_settings
                    (organization_id, default_locale, time_zone, public_url, password_min_length,
                     login_max_attempts, login_lockout_minutes, session_timeout_minutes,
                     invitation_validity_hours)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (organization_id) do update set
                    default_locale = excluded.default_locale,
                    time_zone = excluded.time_zone,
                    public_url = excluded.public_url,
                    password_min_length = excluded.password_min_length,
                    login_max_attempts = excluded.login_max_attempts,
                    login_lockout_minutes = excluded.login_lockout_minutes,
                    session_timeout_minutes = excluded.session_timeout_minutes,
                    invitation_validity_hours = excluded.invitation_validity_hours,
                    updated_at = now()
                """)
                .params(s.organizationId(), s.defaultLocale(), s.timeZone(), s.publicUrl(),
                        s.passwordMinLength(), s.loginMaxAttempts(), s.loginLockoutMinutes(),
                        s.sessionTimeoutMinutes(), s.invitationValidityHours())
                .update();
    }

    public boolean slugTaken(String slug) {
        return jdbc.sql("select exists (select 1 from organization where slug = ?)")
                .param(slug)
                .query(Boolean.class)
                .single();
    }

    private static Organization organization(ResultSet rs, int row) throws SQLException {
        return new Organization(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("slug"),
                rs.getTimestamp("created_at").toInstant());
    }
}
