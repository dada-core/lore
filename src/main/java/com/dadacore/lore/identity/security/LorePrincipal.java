package com.dadacore.lore.identity.security;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * What the session remembers about the signed-in person: identifiers only. Name, role and status
 * are read from the database on every request.
 *
 * <p>{@link #getName()} returns the account identifier, which indexes the sessions in PostgreSQL:
 * this is how all the sessions of an account are found and closed.
 */
public record LorePrincipal(UUID userId, UUID organizationId)
        implements AuthenticatedPrincipal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public String getName() {
        return userId.toString();
    }
}
