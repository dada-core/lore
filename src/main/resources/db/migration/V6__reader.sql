-- Modules library and audit: the reader. The rendering of each version for reading, the views of
-- the documents in the audit log, and a new extraction of the documents imported before.

-- Module library: a version laid out for reading (Markdown, Word, text), produced by the
-- extraction process and cleaned by Lore. A PDF has none: the reader shows the file itself.
create table rendering (
    version_id uuid primary key references document_version (id) on delete cascade,
    -- The number of the rules of rendering: a later version of Lore may extract again the versions
    -- rendered with older rules.
    renderer   integer not null check (renderer >= 1),
    part_count integer not null check (part_count >= 1),
    -- True when the rendering stops before the end of the document.
    truncated  boolean not null default false,
    -- Each anchor of the rendering (h-7, part-3), the part that holds it, the level and the text of
    -- its title: the outline of the document, and the part to open for an anchor.
    places     jsonb not null default '[]'::jsonb,
    created_at timestamptz not null default now()
);

-- A long document is shown part by part: a part holds about 500,000 characters of HTML.
create table rendering_part (
    version_id uuid not null references rendering (version_id) on delete cascade,
    number     integer not null check (number >= 1),
    html       text not null,
    primary key (version_id, number)
);

-- Module audit: the views of a person, for "once an hour" and for "Resume" on the home page.
create index audit_event_viewed_idx on audit_event (organization_id, actor_id, occurred_at desc)
    where action = 'document.viewed';

-- The documents imported before the reader: the current version of each Markdown, Word and text
-- document indexed is extracted again, to receive its rendering. A PDF has nothing to redo; a
-- failed version stays failed.
with versions as (
    update document_version v
    set extraction_status = 'PENDING', extraction_error = null, extracted_at = null
    from document d
    where d.current_version_id = v.id
      and v.extraction_status = 'INDEXED'
      and v.media_type in ('text/markdown', 'text/plain',
          'application/vnd.openxmlformats-officedocument.wordprocessingml.document')
    returning v.id, v.sha256, v.media_type, d.space_id
)
insert into extraction_job (version_id, organization_id, sha256, media_type)
select versions.id, s.organization_id, versions.sha256, versions.media_type
from versions join space s on s.id = versions.space_id
on conflict (version_id) do nothing;
