package com.dadacore.lore.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One audit event. The details are stored as structured data (identifiers, title or name at the
 * time of the operation) and turned into a sentence when displayed, in the reader's language.
 *
 * @param actorId null when the operation was made by the system, for example by a server command
 */
public record AuditEntry(
        UUID organizationId,
        UUID actorId,
        String action,
        String resourceType,
        String resourceId,
        String ip,
        Map<String, Object> details) {

    public static Builder of(UUID organizationId, UUID actorId, String action) {
        return new Builder(organizationId, actorId, action);
    }

    public static final class Builder {

        private final UUID organizationId;
        private final UUID actorId;
        private final String action;
        private String resourceType;
        private String resourceId;
        private String ip;
        private final Map<String, Object> details = new LinkedHashMap<>();

        private Builder(UUID organizationId, UUID actorId, String action) {
            this.organizationId = organizationId;
            this.actorId = actorId;
            this.action = action;
        }

        public Builder resource(String type, Object id) {
            this.resourceType = type;
            this.resourceId = id == null ? null : id.toString();
            return this;
        }

        public Builder ip(String ip) {
            this.ip = ip;
            return this;
        }

        public Builder detail(String name, Object value) {
            if (value != null) {
                details.put(name, value instanceof UUID ? value.toString() : value);
            }
            return this;
        }

        public AuditEntry build() {
            return new AuditEntry(organizationId, actorId, action, resourceType, resourceId, ip,
                    Map.copyOf(details));
        }
    }
}
