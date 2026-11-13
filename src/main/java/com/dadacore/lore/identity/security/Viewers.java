package com.dadacore.lore.identity.security;

import java.util.Optional;
import java.util.UUID;

import com.dadacore.lore.identity.OrganizationRole;
import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.shared.RequestInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Loads the person behind the current session, with fresh data: a deactivated account, or one
 * removed from the organization, no longer has a valid session.
 */
@Component
public class Viewers {

    private final JdbcClient jdbc;

    public Viewers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Viewer> current(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof LorePrincipal principal)) {
            return Optional.empty();
        }
        HttpSession session = request.getSession(false);
        Optional<Viewer> viewer = load(principal.userId(), principal.organizationId(),
                session == null ? null : session.getId(), RequestInfo.clientIp(request));
        if (viewer.isEmpty() && session != null) {
            session.invalidate();
            SecurityContextHolder.clearContext();
        }
        return viewer;
    }

    private Optional<Viewer> load(UUID userId, UUID organizationId, String sessionId, String ip) {
        return jdbc.sql("""
                select u.id, u.display_name, u.email, coalesce(u.locale, s.default_locale) as locale, m.role
                from user_account u
                join membership m on m.user_id = u.id and m.organization_id = ?
                join organization_settings s on s.organization_id = m.organization_id
                where u.id = ? and u.status = 'ACTIVE'
                """)
                .params(organizationId, userId)
                .query((rs, row) -> new Viewer(
                        rs.getObject("id", UUID.class),
                        organizationId,
                        OrganizationRole.valueOf(rs.getString("role")),
                        rs.getString("display_name"),
                        rs.getString("email"),
                        rs.getString("locale"),
                        sessionId,
                        ip))
                .optional();
    }
}
