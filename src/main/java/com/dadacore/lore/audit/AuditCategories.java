package com.dadacore.lore.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The categories of the audit log, computed from the action of each event, never stored: an
 * action added later takes its category here, and the events already recorded with it.
 */
public final class AuditCategories {

    /** The category of an action this list does not know. */
    public static final String OTHER = "other";

    /** Each category and its actions, in the order of the screen. */
    private static final Map<String, Set<String>> ACTIONS;

    static {
        Map<String, Set<String>> actions = new LinkedHashMap<>();
        actions.put("view", Set.of("document.viewed"));
        actions.put("download", Set.of("document.downloaded"));
        actions.put("import", Set.of("document.imported", "document.version_imported"));
        actions.put("status", Set.of("document.status_changed"));
        actions.put("change", Set.of("document.updated", "document.moved"));
        actions.put("deletion", Set.of("document.deleted"));
        actions.put("rights", Set.of("document.access_changed", "document.owner_changed", "member.role_changed",
                "space.member_role_changed"));
        actions.put("members", Set.of("invitation.created", "invitation.revoked", "member.joined", "member.removed",
                "member.disabled", "member.enabled", "member.reset_link_created", "space.member_added",
                "space.member_removed"));
        actions.put("sign_in", Set.of("auth.login_failed", "auth.locked", "auth.password_changed", "auth.email_changed",
                "auth.password_reset"));
        actions.put("organization", Set.of("organization.created", "organization.settings_changed",
                "import.settings_changed", "space.created", "space.updated", "space.archived", "space.unarchived",
                "space.deleted", "folder.created", "folder.renamed", "folder.deleted", "audit.exported"));
        ACTIONS = Collections.unmodifiableMap(actions);
    }

    private AuditCategories() {
    }

    /** The categories, in the order of the screen, without {@link #OTHER}. */
    public static List<String> names() {
        return List.copyOf(ACTIONS.keySet());
    }

    /** True for a category, {@link #OTHER} included. */
    public static boolean exists(String category) {
        return ACTIONS.containsKey(category) || OTHER.equals(category);
    }

    /** The actions of a category; for {@link #OTHER}, every action known, which it is not. */
    public static Set<String> actions(String category) {
        if (OTHER.equals(category)) {
            List<String> known = new ArrayList<>();
            ACTIONS.values().forEach(known::addAll);
            return Set.copyOf(known);
        }
        return ACTIONS.getOrDefault(category, Set.of());
    }

    public static String of(String action) {
        for (Map.Entry<String, Set<String>> category : ACTIONS.entrySet()) {
            if (category.getValue().contains(action)) {
                return category.getKey();
            }
        }
        return OTHER;
    }
}
