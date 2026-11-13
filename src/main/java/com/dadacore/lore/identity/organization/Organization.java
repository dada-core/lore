package com.dadacore.lore.identity.organization;

import java.time.Instant;
import java.util.UUID;

public record Organization(UUID id, String name, String slug, Instant createdAt) {
}
