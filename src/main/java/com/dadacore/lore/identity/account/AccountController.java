package com.dadacore.lore.identity.account;

import java.util.List;
import java.util.Map;

import com.dadacore.lore.identity.Viewer;
import com.dadacore.lore.identity.security.OpenSession;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/account")
@Tag(name = "My account")
class AccountController {

    private final AccountService accountService;

    AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    @Operation(summary = "Profile and preferences of the signed-in person")
    AccountService.AccountView get(Viewer viewer) {
        return accountService.get(viewer);
    }

    @PatchMapping
    @Operation(summary = "Change the name or the interface language")
    AccountService.AccountView updateProfile(Viewer viewer, @RequestBody AccountService.ProfileUpdate body) {
        return accountService.updateProfile(viewer, body);
    }

    @PutMapping("/email")
    @Operation(summary = "Change the sign-in address (current password required)")
    AccountService.AccountView changeEmail(Viewer viewer, @RequestBody AccountService.EmailChange body) {
        return accountService.changeEmail(viewer, body);
    }

    @PutMapping("/password")
    @Operation(summary = "Change the password")
    Map<String, Integer> changePassword(Viewer viewer, @RequestBody AccountService.PasswordChange body) {
        return Map.of("closedSessions", accountService.changePassword(viewer, body));
    }

    @GetMapping("/sessions")
    @Operation(summary = "Open sessions of the signed-in person")
    List<OpenSession> sessions(Viewer viewer) {
        return accountService.sessions(viewer);
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Sign out one session")
    void closeSession(Viewer viewer, @PathVariable String id) {
        accountService.closeSession(viewer, id);
    }

    @PostMapping("/sessions/sign-out-others")
    @Operation(summary = "Sign out every other session")
    Map<String, Integer> closeOtherSessions(Viewer viewer) {
        return Map.of("closedSessions", accountService.closeOtherSessions(viewer));
    }
}
