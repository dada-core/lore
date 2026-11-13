package com.dadacore.lore.identity.members;

import java.io.PrintStream;
import java.util.Locale;
import java.util.Optional;

import com.dadacore.lore.identity.account.MembershipRepository;
import com.dadacore.lore.identity.account.UserAccount;
import com.dadacore.lore.identity.account.UserAccountRepository;
import com.dadacore.lore.identity.organization.Organization;
import com.dadacore.lore.identity.organization.OrganizationRepository;
import com.dadacore.lore.identity.organization.PublicUrl;
import com.dadacore.lore.shared.CliCommand;
import org.springframework.stereotype.Component;

/**
 * {@code lore reset-password <e-mail>}: prints a reset link, for the owner who lost their
 * password when nobody else can generate one. Running it requires access to the server.
 */
@Component
class ResetPasswordCommand implements CliCommand {

    private final OrganizationRepository organizations;
    private final UserAccountRepository accounts;
    private final MembershipRepository memberships;
    private final PasswordResets resets;
    private final PublicUrl publicUrl;

    ResetPasswordCommand(OrganizationRepository organizations, UserAccountRepository accounts,
            MembershipRepository memberships, PasswordResets resets, PublicUrl publicUrl) {
        this.organizations = organizations;
        this.accounts = accounts;
        this.memberships = memberships;
        this.resets = resets;
        this.publicUrl = publicUrl;
    }

    @Override
    public String name() {
        return "reset-password";
    }

    @Override
    public String description() {
        return "Print a password reset link for an account: lore reset-password <e-mail>";
    }

    @Override
    public int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 1) {
            err.println("Usage: lore reset-password <e-mail>");
            return 2;
        }
        Optional<Organization> organization = organizations.installation();
        if (organization.isEmpty()) {
            err.println("Lore is not configured yet: open it in a browser to run the initial setup.");
            return 1;
        }
        String email = args[0].strip().toLowerCase(Locale.ROOT);
        Optional<UserAccount> account = accounts.findByEmail(email)
                .filter(found -> memberships.role(organization.get().id(), found.id()).isPresent());
        if (account.isEmpty()) {
            err.println("No account with this e-mail address in " + organization.get().name() + ".");
            return 1;
        }
        if (!account.get().isActive()) {
            err.println("This account is disabled. Enable it again in Administration, Members.");
            return 1;
        }
        String baseUrl = publicUrl.resolve(organizations.settings(organization.get().id()), null);
        PasswordResets.CreatedLink link = resets.create(organization.get().id(), account.get(), null, null, baseUrl);
        out.println("Password reset link for " + account.get().displayName() + " <" + email + ">:");
        out.println();
        out.println("  " + link.url());
        out.println();
        out.println("Valid for 24 hours, once. Send it through a safe channel.");
        return 0;
    }
}
