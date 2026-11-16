-- Modules library, access and ingestion: documents, their versions and their text, restricted
-- access, and the queue of the text extraction.

-- A document names its folder with its space, so that the folder is always one of that space.
alter table folder add constraint folder_id_space_unique unique (id, space_id);

-- Module library: the documents of a space.
create table document (
    id                 uuid primary key default uuidv7(),
    space_id           uuid not null references space (id) on delete cascade,
    -- Null: at the root of the space ("No folder").
    folder_id          uuid,
    title              text not null check (char_length(title) between 1 and 200),
    description        text check (char_length(description) between 1 and 2000),
    category           text check (category in ('RUNBOOK', 'OPERATING_PROCEDURE', 'INCIDENT_REPORT',
                           'SECURITY', 'VENDOR', 'ARCHITECTURE', 'TECHNICAL')),
    status             text not null check (status in ('DRAFT', 'PUBLISHED', 'OBSOLETE', 'ARCHIVED')),
    -- Seen only by the people chosen, the maintainers of the space, the administrators and its owner.
    restricted         boolean not null default false,
    owner_id           uuid references user_account (id) on delete set null,
    created_by         uuid references user_account (id) on delete set null,
    current_version_id uuid,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    unique (id, space_id),
    -- Without "on delete": a folder that holds documents cannot be deleted, while deleting a space
    -- takes its folders and documents away in the same statement.
    foreign key (folder_id, space_id) references folder (id, space_id)
);

create index document_space_updated_idx on document (space_id, updated_at desc);
create index document_folder_idx on document (folder_id);
-- The imports of a person still in progress or failed, for the home page.
create index document_created_by_idx on document (created_by);

-- Module library: each import of a file is a version. The file itself is on disk, under its
-- SHA-256 in the folder of the organization (module storage).
create table document_version (
    id                uuid primary key default uuidv7(),
    document_id       uuid not null references document (id) on delete cascade,
    number            integer not null check (number >= 1),
    sha256            text not null check (sha256 ~ '^[0-9a-f]{64}$'),
    -- The name of the file on the computer it came from, for display only.
    filename          text not null check (char_length(filename) between 1 and 255),
    media_type        text not null check (media_type in ('application/pdf',
                          'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
                          'text/markdown', 'text/plain')),
    size_bytes        bigint not null check (size_bytes > 0),
    author_id         uuid references user_account (id) on delete set null,
    created_at        timestamptz not null default now(),
    extraction_status text not null default 'PENDING'
                          check (extraction_status in ('PENDING', 'INDEXED', 'SCANNED', 'FAILED')),
    extraction_error  text,
    extracted_at      timestamptz,
    language          text check (language in ('fr', 'en', 'other')),
    page_count        integer,
    section_count     integer,
    text_truncated    boolean not null default false,
    unique (document_id, number),
    unique (id, document_id),
    check ((extraction_status = 'FAILED') = (extraction_error is not null))
);

create index document_version_sha256_idx on document_version (sha256);

-- The current version is one of the versions of the document.
alter table document add constraint document_current_version_fk
    foreign key (current_version_id, id) references document_version (id, document_id);

-- Module library: the text of a version, cut into passages (chunks) that keep their place.
create table chunk (
    id         bigint generated always as identity primary key,
    version_id uuid not null references document_version (id) on delete cascade,
    ordinal    integer not null,
    -- page-12, h-7, part-3: where the passage is in the document.
    anchor     text not null,
    page       integer,
    headings   text[] not null default '{}',
    content    text not null,
    unique (version_id, ordinal)
);

-- Module access: the people chosen for a restricted document. They must be members of the
-- space: leaving the space, or the organization, removes their access.
create table document_grant (
    document_id uuid not null,
    space_id    uuid not null,
    user_id     uuid not null,
    granted_by  uuid references user_account (id) on delete set null,
    created_at  timestamptz not null default now(),
    primary key (document_id, user_id),
    foreign key (document_id, space_id) references document (id, space_id) on delete cascade,
    foreign key (space_id, user_id) references space_member (space_id, user_id) on delete cascade
);

create index document_grant_space_user_idx on document_grant (space_id, user_id);

-- Module ingestion: the extractions to run. A job ends with its deletion; the version keeps the
-- result. The organization, the SHA-256 and the type are copied: the module does not read the
-- tables of the documents.
create table extraction_job (
    version_id      uuid primary key references document_version (id) on delete cascade,
    organization_id uuid not null references organization (id) on delete cascade,
    sha256          text not null,
    media_type      text not null,
    status          text not null default 'PENDING' check (status in ('PENDING', 'RUNNING')),
    attempts        integer not null default 0,
    run_after       timestamptz not null default now(),
    -- While RUNNING: the lease of the worker, which another one takes over once it expires.
    locked_until    timestamptz,
    lease           uuid,
    last_error      text,
    created_at      timestamptz not null default now()
);

create index extraction_job_pending_idx on extraction_job (run_after) where status = 'PENDING';
create index extraction_job_running_idx on extraction_job (locked_until) where status = 'RUNNING';

-- Module library: the maximum size of a file, chosen by the administrators (50 MB when null),
-- always within the ceiling set by LORE_MAX_UPLOAD_SIZE.
create table import_settings (
    organization_id uuid primary key references organization (id) on delete cascade,
    max_upload_mb   integer check (max_upload_mb between 1 and 1024),
    updated_at      timestamptz not null default now()
);
