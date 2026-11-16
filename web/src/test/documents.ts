// What the fake server answers about spaces and documents, for the tests of the interface.

export const DOCX = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
export const PDF = 'application/pdf';
export const MARKDOWN = 'text/markdown';

export const eli = {
  id: '0193b1f2-0000-7000-8000-000000000002', displayName: 'Eli Brou', email: 'eli.brou@dada.group', locale: 'fr', role: 'MEMBER',
};
export const herve = { id: '0193b1f2-0000-7000-8000-000000000003', displayName: 'Hervé Coulibaly', email: 'herve.coulibaly@dada.group' };
export const christ = { id: '0193b1f2-0000-7000-8000-000000000004', displayName: 'Christ Koffi', email: 'christ.koffi@dada.group' };
export const amenanPerson = { id: '0193b1f2-0000-7000-8000-000000000001', displayName: 'Amenan Souaga', email: 'amenan.souaga@dada.group' };

export const SPACE_ID = '0193b1f2-0000-7000-8000-0000000000a1';
export const PROCEDURES = '0193b1f2-0000-7000-8000-0000000000f1';
export const RUNBOOKS = '0193b1f2-0000-7000-8000-0000000000f2';

export function space(overrides: Record<string, unknown> = {}) {
  return {
    id: SPACE_ID, name: 'Infrastructure', slug: 'infrastructure', description: null, color: '#13705E', role: 'CONTRIBUTOR',
    memberCount: 4, documentCount: 0, createdAt: '2026-11-13T09:00:00Z', lastActivityAt: '2026-11-13T09:00:00Z', archivedAt: null,
    ...overrides,
  };
}

export const folders = [
  { id: PROCEDURES, name: 'Procédures', createdAt: '2026-11-13T09:00:00Z' },
  { id: RUNBOOKS, name: 'Runbooks', createdAt: '2026-11-13T09:00:00Z' },
];

export function member(person: { id: string; displayName: string; email: string }, role: string, self = false) {
  return { id: person.id, displayName: person.displayName, email: person.email, role, active: true, self, addedAt: '2026-11-13T09:00:00Z' };
}

/** A document as GET /documents/{id} and the import answer it. */
export function view(overrides: Record<string, unknown> = {}, version: Record<string, unknown> = {}) {
  return {
    id: 'd0000000-0000-7000-8000-000000000001',
    title: 'Plan reprise 2026',
    description: null,
    category: null,
    status: 'DRAFT',
    restricted: false,
    space: { id: SPACE_ID, name: 'Infrastructure', slug: 'infrastructure', color: '#13705E' },
    folder: null,
    owner: { id: eli.id, displayName: eli.displayName },
    createdAt: '2026-11-16T09:00:00Z',
    updatedAt: '2026-11-16T09:00:00Z',
    currentVersion: {
      id: 'e0000000-0000-7000-8000-000000000001', number: 1, filename: 'plan-reprise-2026.docx', mediaType: DOCX,
      sizeBytes: 1_258_291, language: null, pageCount: null, sectionCount: null, extractionStatus: 'PENDING',
      extractionError: null, textTruncated: false, extractedAt: null, createdAt: '2026-11-16T09:00:00Z',
      author: { id: eli.id, displayName: eli.displayName },
      ...version,
    },
    actions: { edit: true, delete: true, manageAccess: true, retry: false },
    duplicateOf: null,
    ...overrides,
  };
}

/** A document as the lists answer it. */
export function item(overrides: Record<string, unknown> = {}) {
  return {
    id: 'd0000000-0000-7000-8000-000000000001', title: 'Plan reprise 2026', folderId: null, category: null, status: 'DRAFT',
    restricted: false, owner: { id: eli.id, displayName: eli.displayName }, updatedAt: '2026-11-16T09:00:00Z', version: 1,
    filename: 'plan-reprise-2026.docx', mediaType: DOCX, sizeBytes: 1_258_291, extractionStatus: 'INDEXED', extractionError: null,
    language: 'fr', pageCount: null, sectionCount: 14,
    ...overrides,
  };
}

export function page(items: unknown[], total = items.length) {
  return { items, page: 0, size: 200, total };
}

export const limits = { maxMegabytes: 50, maxBytes: 50 * 1024 * 1024, extensions: ['pdf', 'docx', 'md', 'markdown', 'txt'] };

/** A file of the given size, filled with letters. */
export function file(name: string, size = 120): File {
  return new File(['a'.repeat(size)], name);
}
