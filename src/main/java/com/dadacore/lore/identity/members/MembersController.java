package com.dadacore.lore.identity.members;

import java.util.UUID;

import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.identity.organization.OrganizationRepository;
import com.dadacore.lore.identity.organization.PublicUrl;
import com.dadacore.lore.shared.PageQuery;
import com.dadacore.lore.shared.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reserved to owners and administrators ({@code 403 admin_only} otherwise).
 */
@RestController
@RequestMapping("/api/v1/admin/members")
@Tag(name = "Administration")
class MembersController {

    private final MembersService members;
    private final OrganizationRepository organizations;
    private final PublicUrl publicUrl;

    MembersController(MembersService members, OrganizationRepository organizations, PublicUrl publicUrl) {
        this.members = members;
        this.organizations = organizations;
        this.publicUrl = publicUrl;
    }

    @GetMapping
    @Operation(summary = "Members of the organization, by name; q searches names and addresses")
    PageResponse<MembersService.MemberView> list(Viewer viewer, @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return members.list(viewer, q, PageQuery.of(page, size));
    }

    record RoleChange(String role) {
    }

    /** {@code 403 owner_only}, {@code 409 last_owner}. */
    @PatchMapping("/{id}")
    @Operation(summary = "Change the role of a member")
    MembersService.MemberView changeRole(Viewer viewer, @PathVariable UUID id, @RequestBody RoleChange body) {
        return members.changeRole(viewer, id, body.role());
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "Disable an account: it can no longer sign in, its sessions are closed")
    MembersService.MemberView disable(Viewer viewer, @PathVariable UUID id) {
        return members.disable(viewer, id);
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "Enable a disabled account again")
    MembersService.MemberView enable(Viewer viewer, @PathVariable UUID id) {
        return members.enable(viewer, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a member from the organization")
    void remove(Viewer viewer, @PathVariable UUID id) {
        members.remove(viewer, id);
    }

    @PostMapping("/{id}/password-reset-link")
    @Operation(summary = "Generate a password reset link, valid 24 hours, once")
    PasswordResets.CreatedLink resetLink(Viewer viewer, @PathVariable UUID id, HttpServletRequest request) {
        String baseUrl = publicUrl.resolve(organizations.settings(viewer.organizationId()), request);
        return members.resetLink(viewer, id, baseUrl);
    }
}
