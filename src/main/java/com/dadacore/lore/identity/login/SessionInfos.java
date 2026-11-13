package com.dadacore.lore.identity.login;

import java.util.Optional;

import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.identity.organization.Organization;
import com.dadacore.lore.identity.organization.OrganizationRepository;
import com.dadacore.lore.identity.organization.OrganizationSettings;
import com.dadacore.lore.identity.security.Viewers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class SessionInfos {

    private final OrganizationRepository organizations;
    private final Viewers viewers;

    public SessionInfos(OrganizationRepository organizations, Viewers viewers) {
        this.organizations = organizations;
        this.viewers = viewers;
    }

    public SessionInfo current(HttpServletRequest request) {
        Optional<Organization> organization = organizations.installation();
        if (organization.isEmpty()) {
            return new SessionInfo(true, null, null);
        }
        OrganizationSettings settings = organizations.settings(organization.get().id());
        SessionInfo.CurrentUser user = viewers.current(request).map(SessionInfos::user).orElse(null);
        return new SessionInfo(false,
                new SessionInfo.OrganizationSummary(organization.get().name(), settings.defaultLocale(),
                        settings.timeZone()),
                user);
    }

    private static SessionInfo.CurrentUser user(Viewer viewer) {
        return new SessionInfo.CurrentUser(viewer.userId(), viewer.displayName(), viewer.email(),
                viewer.locale(), viewer.role().name());
    }
}
