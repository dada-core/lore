-- Modules library and access: spaces, their folders, and the roles of their members.

-- Module library: the spaces of an organization. Owners and administrators of the organization
-- see them all; the other members see the spaces they belong to.
create table space (
    id              uuid primary key default uuidv7(),
    organization_id uuid not null references organization (id) on delete cascade,
    name            text not null,
    -- The address of the space in the interface (/spaces/<slug>); the API uses the identifier.
    slug            text not null,
    description     text,
    color           text not null check (color ~ '^#[0-9A-F]{6}$'),
    created_by      uuid references user_account (id) on delete set null,
    created_at      timestamptz not null default now(),
    -- Last change of the space or of its folders, shown as its last activity.
    updated_at      timestamptz not null default now(),
    -- Null while the space is active. An archived space is only seen by its maintainers.
    archived_at     timestamptz,
    unique (organization_id, slug),
    -- Target of the roles in the space, which must belong to the same organization.
    unique (id, organization_id)
);

-- Module library: one level of folders, in the order of their creation.
create table folder (
    id         uuid primary key default uuidv7(),
    space_id   uuid not null references space (id) on delete cascade,
    name       text not null,
    created_at timestamptz not null default now()
);

create unique index folder_space_name_idx on folder (space_id, lower(name));

-- Module library: the spaces an invitation adds its recipient to, with their role.
create table invitation_space (
    invitation_id uuid not null references invitation (id) on delete cascade,
    space_id      uuid not null references space (id) on delete cascade,
    role          text not null check (role in ('READER', 'CONTRIBUTOR', 'MAINTAINER')),
    primary key (invitation_id, space_id)
);

-- Module access: the members of each space. A role in a space depends on the membership of the
-- organization of the space: leaving the organization removes it, even when a role is being given
-- at that moment.
create table space_member (
    space_id        uuid not null,
    user_id         uuid not null references user_account (id) on delete cascade,
    organization_id uuid not null references organization (id) on delete cascade,
    role            text not null check (role in ('READER', 'CONTRIBUTOR', 'MAINTAINER')),
    added_by        uuid references user_account (id) on delete set null,
    created_at      timestamptz not null default now(),
    primary key (space_id, user_id),
    foreign key (space_id, organization_id) references space (id, organization_id) on delete cascade,
    foreign key (organization_id, user_id) references membership (organization_id, user_id) on delete cascade
);

create index space_member_organization_user_idx on space_member (organization_id, user_id);
