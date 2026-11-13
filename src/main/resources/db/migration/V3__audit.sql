-- Module audit: sensitive operations. The application never updates or deletes an event;
-- events older than a year will be purged every night.

create table audit_event (
    id              bigint generated always as identity primary key,
    organization_id uuid not null,
    -- Null: operation made by the system, for example by a server command.
    actor_id        uuid,
    action          text not null,
    resource_type   text,
    resource_id     text,
    ip              text,
    -- Identifiers, and names or titles at the time of the operation.
    details         jsonb not null default '{}'::jsonb,
    occurred_at     timestamptz not null default now()
);

create index audit_event_organization_time_idx on audit_event (organization_id, occurred_at desc);
