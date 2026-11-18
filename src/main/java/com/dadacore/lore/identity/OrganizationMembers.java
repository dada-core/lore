package com.dadacore.lore.identity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The members of an organization, for the modules that show or choose people: the members of a
 * space, for example. Someone removed from the organization is no longer found.
 */
public interface OrganizationMembers {

    Optional<OrganizationMember> find(UUID organizationId, UUID userId);

    Map<UUID, OrganizationMember> find(UUID organizationId, Collection<UUID> userIds);

    /**
     * Active members whose name or address contains the search, ignoring case and accents, by name.
     *
     * @param excluded people not to return, for example those already in a space
     */
    List<OrganizationMember> search(UUID organizationId, String search, Collection<UUID> excluded, int limit);

    /**
     * The name and address of these accounts, whether their people are members or have left: the
     * audit log names who did what, even after they left.
     */
    Map<UUID, Person> accounts(Collection<UUID> userIds);
}
