package com.dadacore.lore.identity;

import java.util.UUID;

/** A person with an account, member of the organization or not any more: who acted, in the audit log. */
public record Person(UUID id, String displayName, String email) {
}
