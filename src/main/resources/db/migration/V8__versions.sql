-- Modules library and audit: the versions of a document, its statuses, and the reads of the audit
-- log. Nothing is rewritten: every document has one version, its current one.

-- What a person writes about a new version, at its import; none for the first.
alter table document_version
    add column note text check (char_length(note) between 1 and 500),
    -- The share of passages changed from the version before, from 0 to 1, computed when the version
    -- becomes current; null when it cannot be known (a scan, a failure, a version never current).
    add column change_ratio real check (change_ratio between 0 and 1),
    -- The status the document takes when this version becomes current; null: unchanged.
    add column status_when_current text check (status_when_current in ('DRAFT', 'PUBLISHED'));

-- The first version of a document changed all of it.
update document_version v set change_ratio = 1
from document d
where d.current_version_id = v.id and v.number = 1 and v.extraction_status in ('INDEXED', 'SCANNED');

-- An obsolete document may name the one that replaces it; deleting that one removes the link.
alter table document add column replaced_by_id uuid references document (id) on delete set null;
alter table document add constraint document_replaced_by_check
    check ((replaced_by_id is null or status = 'OBSOLETE') and replaced_by_id is distinct from id);
create index document_replaced_by_idx on document (replaced_by_id) where replaced_by_id is not null;

-- Since when the document has its status ("obsolete since..."). No status changed before this
-- version: the date of each document is its creation.
alter table document add column status_changed_at timestamptz;
update document set status_changed_at = created_at;
alter table document alter column status_changed_at set not null, alter column status_changed_at set default now();

-- Module audit. The page of one person; the activity of a document, without its views and
-- downloads (a tenth of the events, measured on a million); the last imports, versions and statuses,
-- for the home page.
create index audit_event_actor_idx on audit_event (organization_id, actor_id, occurred_at desc);
create index audit_event_resource_idx on audit_event (organization_id, resource_type, resource_id, occurred_at desc)
    where action not in ('document.viewed', 'document.downloaded');
create index audit_event_documents_idx on audit_event (organization_id, occurred_at desc)
    where action in ('document.imported', 'document.version_imported', 'document.status_changed');
