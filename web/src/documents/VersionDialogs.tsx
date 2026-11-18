import { useMutation, useQuery } from '@tanstack/react-query';
import { useEffect, useId, useRef, useState, type DragEvent, type FormEvent } from 'react';
import { api, ApiError, call } from '../api/client';
import { keys, queryClient } from '../api/queryClient';
import { errorMessage, fieldMessage } from '../i18n/errors';
import { useI18n, type I18n } from '../i18n/i18n';
import { useDebounced } from '../ui/bits';
import { Callout, type Problem } from '../ui/forms';
import { Icon, Spinner } from '../ui/Icon';
import { Modal } from '../ui/Modal';
import { useNotify } from '../ui/Toast';
import {
  extensionList,
  extensionOf,
  FormatIcon,
  formatName,
  refreshDocuments,
  sizeText,
  STATUSES,
  StatusLabel,
  useImportLimits,
  versionStatus,
  type Document,
  type DocumentStatus,
} from './documents';
import { check } from './importQueue';
import { uploadVersion, type Upload } from './upload';

/** The format a file will have, from its extension, as the server will detect it. */
const MEDIA_TYPES: Record<string, string> = {
  pdf: 'application/pdf',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  md: 'text/markdown',
  markdown: 'text/markdown',
  txt: 'text/plain',
};

/** After an import of a version or a change of status: what shows the document and its history. */
async function refreshDocument(document: Document, changed: Document): Promise<void> {
  // A reading asked before this change, every 10 s while a version is extracted, would bring the former state back.
  await queryClient.cancelQueries({ queryKey: keys.document(document.id), exact: true });
  queryClient.setQueryData(keys.document(document.id), changed);
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: keys.documentVersions(document.id) }),
    queryClient.invalidateQueries({ queryKey: keys.documentActivity(document.id) }),
    queryClient.invalidateQueries({ queryKey: keys.homeActivity }),
    queryClient.invalidateQueries({ queryKey: keys.attention }),
    refreshDocuments(document.space.id),
  ]);
}

/** "Non importé : le fichier est vide.", from a check made before sending or the answer of the server. */
function refusal(i18n: I18n, problem: Problem, size: number): string {
  const { t } = i18n;
  if (problem.code === 'file_too_large') {
    const max = problem.params?.max;
    return max === undefined ? t('import.refused.tooLarge') : t('import.refused.file_too_large', { size: sizeText(i18n, size), max });
  }
  if (problem.code === 'unsupported_format') {
    return t('import.refused.unsupported_format', { formats: extensionList(String(problem.params?.formats ?? '')) });
  }
  if (['empty_file', 'legacy_word', 'content_mismatch', 'storage_full'].includes(problem.code)) {
    return t(`import.refused.${problem.code as 'empty_file'}`);
  }
  return fieldMessage(i18n, { code: problem.code, params: problem.params ?? {} });
}

/**
 * A new version of the document: its file, what changes, and the status the document takes when
 * the version becomes current, once its text is extracted. The current version stays until then.
 */
export function NewVersionDialog({ document, onClose }: { document: Document; onClose: () => void }) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const limits = useImportLimits();
  const input = useRef<HTMLInputElement>(null);
  const noteId = useId();
  const [file, setFile] = useState<File | null>(null);
  const [note, setNote] = useState('');
  const [checked, setChecked] = useState(document.status === 'PUBLISHED');
  const [over, setOver] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [progress, setProgress] = useState<number | null>(null);
  const upload = useRef<Upload | null>(null);
  const current = document.currentVersion;
  const number = Math.max(current.number, document.newerVersion?.number ?? 0) + 1;
  const sending = progress !== null;
  // The format of the file chosen, when it differs from the current version.
  const chosenType = file ? MEDIA_TYPES[extensionOf(file.name)] : undefined;
  const formatChanges = chosenType !== undefined && chosenType !== current.mediaType;

  useEffect(() => () => upload.current?.abort(), []);

  const choose = (chosen: File | undefined) => {
    if (!chosen) return;
    setFile(chosen);
    const found = check(chosen, limits.data);
    setProblem(found ? refusal(i18n, found, chosen.size) : null);
  };

  const drop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setOver(false);
    choose(event.dataTransfer.files[0]);
  };

  // Cancelled once the file is sent whole, the version may exist anyway: the page asks the server again.
  const cancel = () => {
    if (upload.current) {
      upload.current.abort();
      void queryClient.invalidateQueries({ queryKey: keys.document(document.id), exact: true });
      void queryClient.invalidateQueries({ queryKey: keys.documentVersions(document.id) });
    }
    onClose();
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (sending) return;
    if (!file) {
      setProblem(t('version.fileRequired'));
      return;
    }
    const found = check(file, limits.data);
    if (found) {
      setProblem(refusal(i18n, found, file.size));
      return;
    }
    setProblem(null);
    setProgress(0);
    upload.current = uploadVersion(document.id, file, note, versionStatus(document.status, checked),
      (fraction) => setProgress(Math.round(fraction * 100)));
    try {
      const changed = await upload.current.done;
      await refreshDocument(document, changed);
      notify(t('version.imported'));
      onClose();
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return;
      setProgress(null);
      setProblem(answer(i18n, error, file.size));
    } finally {
      upload.current = null;
    }
  };

  return (
    <Modal
      title={t('version.newTitle', { title: document.title })}
      onClose={cancel}
      onSubmit={(event) => void submit(event)}
      keepOpen={sending}
      footer={(
        <>
          <button type="button" className="btn" onClick={cancel}>{t('common.cancel')}</button>
          <button type="submit" className="btn btn-primary" disabled={sending} data-autofocus={file ? true : undefined}>
            {sending && <Spinner />}
            {t('version.import', { number })}
          </button>
        </>
      )}
    >
      {problem && <Callout tone="error" role="alert">{problem}</Callout>}
      <div
        className={over ? 'drop is-over' : 'drop'}
        onDragOver={(event) => { event.preventDefault(); setOver(true); }}
        onDragLeave={() => setOver(false)}
        onDrop={drop}
      >
        <Icon name="upload" />
        {file ? (
          <p><FormatIcon mediaType={chosenType ?? current.mediaType} /> <strong>{file.name}</strong>, {sizeText(i18n, file.size)}</p>
        ) : (
          <p>
            {t('version.drop', { number })}{' '}
            <button type="button" className="link" onClick={() => input.current?.click()}>{t('version.choose')}</button>.
          </p>
        )}
        {file && (
          <p><button type="button" className="link" disabled={sending} onClick={() => input.current?.click()}>{t('version.choose')}</button></p>
        )}
        <p className="help">
          {t('version.sameFormat', { format: formatName(i18n, current.mediaType), size: limits.data?.maxMegabytes ?? 50 })}
        </p>
        <input
          ref={input}
          type="file"
          className="sr-only"
          tabIndex={-1}
          aria-label={t('version.drop', { number })}
          accept={limits.data?.extensions.map((extension) => `.${extension}`).join(',')}
          onChange={(event) => choose(event.target.files?.[0])}
        />
      </div>
      {formatChanges && (
        <Callout role="status">{t('version.formatChanges', { before: formatName(i18n, current.mediaType), after: formatName(i18n, chosenType) })}</Callout>
      )}
      {sending && (
        <div className="version-progress" role="progressbar" aria-valuenow={progress} aria-valuemin={0} aria-valuemax={100} aria-label={t('version.import', { number })}>
          <span style={{ width: `${progress}%` }} />
        </div>
      )}
      <div className="field">
        <label htmlFor={noteId}>{t('version.note')}</label>
        <textarea
          id={noteId}
          className="textarea"
          maxLength={500}
          value={note}
          placeholder={t('version.notePlaceholder')}
          aria-describedby={`${noteId}-help`}
          onChange={(event) => setNote(event.target.value)}
        />
        <span className="help" id={`${noteId}-help`}>{t('version.noteHelp')}</span>
      </div>
      {document.status === 'PUBLISHED' && (
        <label className="check">
          <input type="checkbox" checked={checked} onChange={(event) => setChecked(event.target.checked)} />
          {t('version.keepPublished')}
        </label>
      )}
      {document.status === 'OBSOLETE' && (
        <label className="check">
          <input type="checkbox" checked={checked} onChange={(event) => setChecked(event.target.checked)} />
          {t('version.republish')}
        </label>
      )}
      <Callout>{t('version.previousKept', { number: current.number })}</Callout>
    </Modal>
  );
}

/** What the server answered to a version refused: the same file as the latest, a file refused, a note too long. */
function answer(i18n: I18n, error: unknown, size: number): string {
  if (!(error instanceof ApiError)) return errorMessage(i18n, error);
  if (error.code === 'same_content') {
    const version = error.number('version');
    return version === undefined ? i18n.t('error.same_content') : i18n.t('version.sameContent', { version });
  }
  if (error.status === 413) {
    const max = error.number('maxMegabytes');
    return refusal(i18n, { code: 'file_too_large', params: max === undefined ? {} : { max } }, size);
  }
  if (error.status === 507) return refusal(i18n, { code: 'storage_full' }, size);
  const field = error.fieldErrors[0];
  if (field?.field === 'file') return refusal(i18n, { code: field.code, params: field.params }, size);
  return field ? fieldMessage(i18n, field) : errorMessage(i18n, error);
}

/**
 * The status of the document: Draft, Published or Obsolete for the contributors, with the document
 * that replaces an obsolete one; Archived for the maintainers of its space.
 */
export function StatusDialog({ document, initial, onClose }: {
  document: Document;
  /** The status chosen when the window opens: Archived for "Archive". */
  initial?: DocumentStatus;
  onClose: () => void;
}) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const name = useId();
  const [status, setStatus] = useState<string>(initial ?? document.status);
  const [replacement, setReplacement] = useState<{ id: string; title: string } | null>(document.replacedBy ?? null);
  const { actions } = document;

  const save = useMutation({
    mutationFn: () => call(() => api.PUT('/api/v1/documents/{id}/status', {
      params: { path: { id: document.id } },
      body: { status, replacedBy: status === 'OBSOLETE' ? replacement?.id : undefined },
    })),
    onSuccess: async (changed) => {
      await refreshDocument(document, changed);
      notify(t('status.saved'));
      onClose();
    },
  });
  const error = save.error instanceof ApiError ? save.error : null;
  const replacementError = error?.fieldErrors.find((problem) => problem.field === 'replacedBy');

  return (
    <Modal
      title={t('status.change')}
      onClose={onClose}
      onSubmit={(event) => {
        event.preventDefault();
        save.mutate();
      }}
      footer={(
        <>
          <button type="button" className="btn" onClick={onClose}>{t('common.cancel')}</button>
          <button type="submit" className="btn btn-primary" disabled={save.isPending}>
            {save.isPending && <Spinner />}
            {t('common.save')}
          </button>
        </>
      )}
    >
      <p className="help">{document.title}</p>
      {save.isError && !replacementError && <Callout tone="error" role="alert">{errorMessage(i18n, save.error)}</Callout>}
      <div role="radiogroup" aria-label={t('status.change')} className="radio-list">
        {STATUSES.map((value) => {
          // Archived, and out of it, belong to the maintainers of the space.
          const reserved = (value === 'ARCHIVED' || document.status === 'ARCHIVED') && !actions.archive;
          return (
            <div key={value}>
              <label className="radio-card" style={reserved ? { opacity: 0.55, cursor: 'not-allowed' } : undefined}>
                <input
                  type="radio"
                  name={name}
                  value={value}
                  checked={status === value}
                  disabled={reserved}
                  aria-describedby={`${name}-${value}`}
                  onChange={() => setStatus(value)}
                />
                <span>
                  <strong><StatusLabel status={value} /></strong>
                  <span className="help" id={`${name}-${value}`}>
                    {t(`status.help.${value}`)}
                    {reserved && value === 'ARCHIVED' && <> {t('status.maintainersOnly')}</>}
                  </span>
                </span>
              </label>
              {value === 'OBSOLETE' && status === 'OBSOLETE' && (
                <ReplacementPicker
                  document={document}
                  chosen={replacement}
                  onChosen={setReplacement}
                  error={replacementError ? fieldMessage(i18n, replacementError) : null}
                />
              )}
            </div>
          );
        })}
      </div>
    </Modal>
  );
}

/** The document that replaces an obsolete one, found by its title among those the person reads. */
function ReplacementPicker({ document, chosen, onChosen, error }: {
  document: Document;
  chosen: { id: string; title: string } | null;
  onChosen: (chosen: { id: string; title: string } | null) => void;
  error: string | null;
}) {
  const i18n = useI18n();
  const { t } = i18n;
  const fieldId = useId();
  const field = useRef<HTMLInputElement>(null);
  const remove = useRef<HTMLButtonElement>(null);
  // The button chosen goes away with the list: the focus goes to what replaces it.
  const moved = useRef(false);
  useEffect(() => {
    if (!moved.current) return;
    moved.current = false;
    (chosen ? remove.current : field.current)?.focus();
  }, [chosen]);
  const pick = (value: { id: string; title: string } | null) => {
    moved.current = true;
    onChosen(value);
  };
  const [typed, setTyped] = useState('');
  const asked = useDebounced(typed.trim(), 150);
  const titles = useQuery({
    queryKey: ['search', 'titles', asked, 'replacement'],
    queryFn: ({ signal }) => call(() => api.GET('/api/v1/search/titles', { params: { query: { q: asked, size: 8 } }, signal })),
    enabled: asked !== '' && asked.length <= 100,
    retry: false,
  });
  const found = (titles.data ?? []).filter((item) => item.id !== document.id && item.status !== 'OBSOLETE');

  return (
    <div className="field replacement-pick">
      {chosen ? <span className="field-label">{t('status.replacedBy')}</span> : <label htmlFor={fieldId}>{t('status.replacedBy')}</label>}
      {chosen ? (
        <div className="row-between">
          <span><Icon name="file-text" /> <bdi>{chosen.title}</bdi></span>
          <button ref={remove} type="button" className="link" onClick={() => pick(null)}>{t('status.removeReplacement')}</button>
        </div>
      ) : (
        <>
          <input
            ref={field}
            id={fieldId}
            className="input"
            value={typed}
            maxLength={100}
            autoComplete="off"
            placeholder={t('status.replacedByPlaceholder')}
            aria-describedby={`${fieldId}-help`}
            aria-invalid={error ? true : undefined}
            onChange={(event) => setTyped(event.target.value)}
          />
          <span className="sr-only" aria-live="polite">{asked ? i18n.tp('palette.results', found.length) : ''}</span>
          {found.length > 0 && (
            <ul className="list" aria-label={t('status.replacedBy')}>
              {found.map((item) => (
                <li key={item.id}>
                  <button type="button" className="list-row" onClick={() => pick({ id: item.id, title: item.title })}>
                    <FormatIcon mediaType={item.mediaType} />
                    <span className="list-main"><bdi className="list-title">{item.title}</bdi></span>
                    <span className="help">{item.space.name}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
      <span className="help" id={`${fieldId}-help`}>{t('status.replacedByHelp')}</span>
      {error && <span className="field-error">{error}</span>}
    </div>
  );
}
