package com.dadacore.lore.identity.organization;

import com.dadacore.lore.identity.Viewer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reserved to owners and administrators ({@code 403 admin_only} otherwise).
 */
@RestController
@RequestMapping("/api/v1/admin/organization")
@Tag(name = "Administration")
class OrganizationSettingsController {

    private final OrganizationSettingsService settings;

    OrganizationSettingsController(OrganizationSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    @Operation(summary = "Settings of the organization")
    OrganizationSettingsService.SettingsView get(Viewer viewer, HttpServletRequest request) {
        return settings.get(viewer, request);
    }

    /** Every change is written to the audit log, with the value before and after. */
    @PatchMapping
    @Operation(summary = "Change settings of the organization")
    OrganizationSettingsService.SettingsView update(Viewer viewer,
            @RequestBody OrganizationSettingsService.SettingsUpdate body, HttpServletRequest request) {
        return settings.update(viewer, body, request);
    }
}
