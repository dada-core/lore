-- Module identity: organizations, accounts, memberships, invitations, password resets.
-- Identifiers use uuidv7() (PostgreSQL 18): ordered by creation, they keep indexes compact.

-- Accent-insensitive search on names ("Herve" finds "Hervé").
create extension if not exists unaccent;

create table organization (
    id         uuid primary key default uuidv7(),
    name       text not null,
    slug       text not null unique,
    created_at timestamptz not null default now()
);

create table organization_settings (
    organization_id           uuid primary key references organization (id) on delete cascade,
    default_locale            text not null default 'fr' check (default_locale in ('fr', 'en')),
    time_zone                 text not null default 'UTC',
    -- Ignored when LORE_PUBLIC_URL is set.
    public_url                text,
    password_min_length       integer not null default 12 check (password_min_length between 8 and 64),
    login_max_attempts        integer not null default 5 check (login_max_attempts between 3 and 20),
    login_lockout_minutes     integer not null default 15 check (login_lockout_minutes > 0),
    session_timeout_minutes   integer not null default 480 check (session_timeout_minutes > 0),
    invitation_validity_hours integer not null default 168 check (invitation_validity_hours > 0),
    updated_at                timestamptz not null default now()
);

create table user_account (
    id            uuid primary key default uuidv7(),
    email         text not null unique check (email = lower(email)),
    display_name  text not null,
    -- Argon2id
    password_hash text not null,
    status        text not null default 'ACTIVE' check (status in ('ACTIVE', 'DISABLED')),
    -- Null: the interface follows the default language of the organization.
    locale        text check (locale in ('fr', 'en')),
    last_login_at timestamptz,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create table membership (
    organization_id uuid not null references organization (id) on delete cascade,
    user_id         uuid not null references user_account (id) on delete cascade,
    role            text not null check (role in ('OWNER', 'ADMIN', 'MEMBER')),
    created_at      timestamptz not null default now(),
    primary key (organization_id, user_id)
);

create index membership_user_idx on membership (user_id);

-- Links are single use. Only the SHA-256 hash of a token is stored.
create table invitation (
    id              uuid primary key default uuidv7(),
    organization_id uuid not null references organization (id) on delete cascade,
    token_hash      bytea not null unique,
    role            text not null check (role in ('ADMIN', 'MEMBER')),
    -- Optional: reserves the link to this address.
    email           text check (email = lower(email)),
    created_by      uuid references user_account (id),
    created_at      timestamptz not null default now(),
    expires_at      timestamptz not null,
    used_at         timestamptz,
    used_by         uuid references user_account (id),
    revoked_at      timestamptz
);

create index invitation_pending_idx on invitation (organization_id)
    where used_at is null and revoked_at is null;

create table password_reset_token (
    id              uuid primary key default uuidv7(),
    user_id         uuid not null references user_account (id) on delete cascade,
    organization_id uuid not null references organization (id) on delete cascade,
    token_hash      bytea not null unique,
    -- Null: generated on the server with "lore reset-password".
    created_by      uuid references user_account (id),
    created_at      timestamptz not null default now(),
    expires_at      timestamptz not null,
    used_at         timestamptz,
    revoked_at      timestamptz
);

create index password_reset_token_user_idx on password_reset_token (user_id);

-- Failed sign-ins, per address and IP ("pair:...") and per address alone ("address:...").
create table login_throttle (
    key               text primary key,
    failures          integer not null,
    window_started_at timestamptz not null,
    locked_until      timestamptz
);
