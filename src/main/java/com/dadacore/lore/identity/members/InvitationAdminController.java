package com.dadacore.lore.identity.members;

import java.util.List;
import java.util.UUID;

import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.identity.organization.OrganizationRepository;
import com.dadacore.lore.identity.organization.PublicUrl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/invitations")
@Tag(name = "Administration")
class InvitationAdminController {

    private final Invitations invitations;
    private final OrganizationRepository organizations;
    private final PublicUrl publicUrl;

    InvitationAdminController(Invitations invitations, OrganizationRepository organizations, PublicUrl publicUrl) {
        this.invitations = invitations;
        this.organizations = organizations;
        this.publicUrl = publicUrl;
    }

    @GetMapping
    @Operation(summary = "Invitations neither used, revoked nor expired")
    List<Invitations.InvitationView> pending(Viewer viewer) {
        return invitations.pending(viewer);
    }

    /** The link is returned only once: only its hash is stored. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an invitation link")
    Invitations.CreatedInvitation create(Viewer viewer, @RequestBody Invitations.NewInvitation body,
            HttpServletRequest request) {
        String baseUrl = publicUrl.resolve(organizations.settings(viewer.organizationId()), request);
        return invitations.create(viewer, body, baseUrl);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke an invitation link")
    void revoke(Viewer viewer, @PathVariable UUID id) {
        invitations.revoke(viewer, id);
    }
}
