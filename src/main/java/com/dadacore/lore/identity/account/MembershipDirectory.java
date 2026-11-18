package com.dadacore.lore.identity.account;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.dadacore.lore.identity.OrganizationMember;
import com.dadacore.lore.identity.OrganizationMembers;
import com.dadacore.lore.identity.Person;
import org.springframework.stereotype.Component;

/** The members of an organization, as the other modules see them. */
@Component
class MembershipDirectory implements OrganizationMembers {

    private final MembershipRepository memberships;

    MembershipDirectory(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    @Override
    public Optional<OrganizationMember> find(UUID organizationId, UUID userId) {
        return memberships.member(organizationId, userId).map(MembershipDirectory::member);
    }

    @Override
    public Map<UUID, OrganizationMember> find(UUID organizationId, Collection<UUID> userIds) {
        Map<UUID, OrganizationMember> members = new LinkedHashMap<>();
        memberships.members(organizationId, userIds).forEach(row -> members.put(row.userId(), member(row)));
        return members;
    }

    @Override
    public List<OrganizationMember> search(UUID organizationId, String search, Collection<UUID> excluded, int limit) {
        return memberships.activeMembers(organizationId, search, excluded, limit).stream()
                .map(MembershipDirectory::member)
                .toList();
    }

    @Override
    public Map<UUID, Person> accounts(Collection<UUID> userIds) {
        Map<UUID, Person> people = new LinkedHashMap<>();
        memberships.accounts(userIds).forEach(person -> people.put(person.id(), person));
        return people;
    }

    private static OrganizationMember member(MemberRow row) {
        return new OrganizationMember(row.userId(), row.displayName(), row.email(), row.role(),
                row.status() == AccountStatus.ACTIVE);
    }
}
