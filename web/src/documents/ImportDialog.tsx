import { useQuery } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState, useSyncExternalStore, type DragEvent, type ReactNode } from 'react';
import { useBlocker, useSearchParams } from 'react-router';
import { api, call, type ApiError } from '../api/client';
import { keys } from '../api/queryClient';
import { errorMessage, fieldMessage } from '../i18n/errors';
import { useI18n, type I18n } from '../i18n/i18n';
import { canContribute, SpaceIcon, useSpaces } from '../spaces/spaces';
import { Avatar } from '../ui/bits';
import { Callout, type Problem } from '../ui/forms';
import { Icon, Spinner, type IconName } from '../ui/Icon';
import { Modal } from '../ui/Modal';
import {
  CATEGORIES,
  extensionList,
  failureCause,
  failureFix,
  fileIcon,
  IMPORT_STATUSES,
  indexedText,
  isPassingFailure,
  refreshSoon,
  sizeText,
  useImportLimits,
} from './documents';
import { ImportQueue, unsent, type Line } from './importQueue';

/**
 * "Import documents": the destination (space, folder, status, category), the people who will
 * see the documents, then the files, dropped or chosen. Each file is checked, sent five at a time
 * with its progress, then followed until its text is extracted. Closing the window or leaving the
 * page while files are being sent asks first. Screen readers hear what happens to each file.
 *
 * @param slug the space of the page it was opened from; elsewhere, the space is chosen first
 */
export function ImportDialog({ slug, onClose }: { slug?: string; onClose: () => void }) {
  const i18n = useI18n();
  const { t } = i18n;
  const [params] = useSearchParams();
  const spaces = useSpaces();
  const limits = useImportLimits();
  const eligible = (spaces.data ?? []).filter(canContribute);
  const fixed = slug ? eligible.find((space) => space.slug === slug) : undefined;
  const [chosenSpace, setChosenSpace] = useState('');
  const spaceId = fixed?.id ?? (chosenSpace || (eligible.length === 1 ? eligible[0].id : ''));
  // Opened from a library filtered on a folder: that folder first.
  const [folderId, setFolderId] = useState(() => {
    const shown = params.get('folder');
    return shown && shown !== 'none' ? shown : '';
  });
  const [status, setStatus] = useState<string>('DRAFT');
  const [category, setCategory] = useState('');
  const [restricted, setRestricted] = useState(false);
  const [chosen, setChosen] = useState<Set<string>>(() => new Set());
  const [dragging, setDragging] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const closed = useRef(false);
  const [queue] = useState(() => new ImportQueue(refreshSoon));
  const lines = useSyncExternalStore(queue.subscribe, queue.snapshot);
  const news = useSyncExternalStore(queue.subscribe, queue.newsSnapshot);

  const folders = useQuery({
    queryKey: keys.folders(spaceId),
    queryFn: () => call(() => api.GET('/api/v1/spaces/{spaceId}/folders', { params: { path: { spaceId } } })),
    enabled: !!spaceId,
  });
  const members = useQuery({
    queryKey: keys.spaceMembers(spaceId),
    queryFn: () => call(() => api.GET('/api/v1/spaces/{spaceId}/members', { params: { path: { spaceId } } })),
    enabled: !!spaceId && restricted,
  });
  const folder = folders.data?.some((one) => one.id === folderId) ? folderId : '';
  // The files that closing would stop: those that have entirely left are imported anyway.
  const left = lines.filter(unsent).length;
  const sending = left > 0;

  // Going back, or any other change of address, while files are being sent: asked first too.
  const shouldBlock = useCallback(() => queue.blocksLeaving(), [queue]);
  const blocker = useBlocker(shouldBlock);
  const blocked = blocker.state === 'blocked';

  useEffect(() => {
    queue.open();
    return () => queue.close();
  }, [queue]);

  // Everything was sent while the question was shown: it goes away, and does not come back by itself.
  useEffect(() => queue.subscribe(() => {
    if (!queue.blocksLeaving()) setConfirming(false);
  }), [queue]);
  useEffect(() => {
    if (blocker.state === 'blocked' && !sending) blocker.reset();
  }, [blocker, sending]);

  // Leaving the page would stop the files being sent: the browser asks first.
  useEffect(() => {
    if (!sending) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [sending]);

  // A file dropped next to the zone is ignored, instead of being opened by the browser.
  useEffect(() => {
    const ignore = (event: globalThis.DragEvent) => {
      if (event.dataTransfer?.types.includes('Files')) event.preventDefault();
    };
    window.addEventListener('dragover', ignore);
    window.addEventListener('drop', ignore);
    return () => {
      window.removeEventListener('dragover', ignore);
      window.removeEventListener('drop', ignore);
    };
  }, []);

  const close = () => {
    if (closed.current) return;
    closed.current = true;
    queue.close();
    onClose();
  };
  const requestClose = () => {
    if (sending) setConfirming(true);
    else close();
  };
  const keepSending = () => {
    setConfirming(false);
    if (blocker.state === 'blocked') blocker.reset();
  };
  const stop = () => {
    if (blocker.state !== 'blocked') {
      close();
      return;
    }
    // The address is already changing: the window goes with it.
    closed.current = true;
    queue.close();
    blocker.proceed();
  };

  const add = (files: FileList | null) => {
    if (!files || files.length === 0 || !spaceId) return;
    setConfirming(false);
    queue.add(Array.from(files), spaceId, {
      folderId: folder || null,
      status,
      category: category || null,
      restricted,
      userIds: restricted ? [...chosen] : [],
    }, limits.data);
  };

  const onDragOver = (event: DragEvent) => {
    if (!spaceId) return;
    event.preventDefault();
    setDragging(true);
  };
  const onDragLeave = (event: DragEvent<HTMLDivElement>) => {
    if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setDragging(false);
  };
  const onDrop = (event: DragEvent) => {
    event.preventDefault();
    setDragging(false);
    add(event.dataTransfer.files);
  };

  const toggle = (id: string) => setChosen((current) => {
    const next = new Set(current);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    return next;
  });

  const accept = limits.data ? limits.data.extensions.map((extension) => `.${extension}`).join(',') : undefined;
  const noSpace = spaces.isSuccess && eligible.length === 0;

  return (
    <>
      <Modal
        title={t('import.title')}
        size="lg"
        keepOpen={sending}
        onClose={requestClose}
        footer={(
          <>
            <span className="left">{t('import.shortcut')}</span>
            <button type="button" className="btn btn-primary" onClick={requestClose}>{t('import.done')}</button>
          </>
        )}
      >
        {spaces.isError && <Callout tone="error" role="alert">{errorMessage(i18n, spaces.error)}</Callout>}
        {limits.isError && <Callout tone="error" role="alert">{errorMessage(i18n, limits.error)}</Callout>}
        {spaces.isPending && <div role="status"><Spinner /><span className="sr-only">{t('common.loading')}</span></div>}
        {noSpace && <Callout>{t('import.noSpace')}</Callout>}
        {spaces.isSuccess && !noSpace && (
          <>
            <div className="target-chips">
              {fixed ? (
                <span className="pill p-gray"><SpaceIcon space={fixed} />{fixed.name}</span>
              ) : (
                <select
                  className="select select-sm"
                  aria-label={t('import.space')}
                  value={spaceId}
                  disabled={lines.length > 0}
                  data-autofocus={spaceId ? undefined : true}
                  onChange={(event) => {
                    setChosenSpace(event.target.value);
                    setFolderId('');
                    setChosen(new Set());
                  }}
                >
                  {!spaceId && <option value="">{t('import.chooseSpace')}</option>}
                  {eligible.map((space) => <option key={space.id} value={space.id}>{space.name}</option>)}
                </select>
              )}
              <span className="muted" aria-hidden="true">›</span>
              <select className="select select-sm" aria-label={t('import.folder')} value={folder} disabled={!spaceId} onChange={(event) => setFolderId(event.target.value)}>
                <option value="">{t('library.noFolder')}</option>
                {(folders.data ?? []).map((one) => <option key={one.id} value={one.id}>{one.name}</option>)}
              </select>
              <select className="select select-sm" aria-label={t('import.status')} value={status} disabled={!spaceId} onChange={(event) => setStatus(event.target.value)}>
                {IMPORT_STATUSES.map((value) => <option key={value} value={value}>{t(`docStatus.${value}`)}</option>)}
              </select>
              <select className="select select-sm" aria-label={t('import.category')} value={category} disabled={!spaceId} onChange={(event) => setCategory(event.target.value)}>
                <option value="">{t('category.none')}</option>
                {CATEGORIES.map((value) => <option key={value} value={value}>{t(`category.${value}`)}</option>)}
              </select>
            </div>

            <div
              className={['drop', dragging && 'is-over', !spaceId && 'is-off'].filter(Boolean).join(' ')}
              onDragEnter={onDragOver}
              onDragOver={onDragOver}
              onDragLeave={onDragLeave}
              onDrop={onDrop}
            >
              <Icon name="upload" />
              {spaceId ? (
                <p>
                  {t('import.drop')}{' '}
                  <button type="button" className="link" data-autofocus onClick={() => input.current?.click()}>{t('import.choose')}</button>.
                </p>
              ) : (
                <p>{t('import.spaceFirst')}</p>
              )}
              <p className="help">{limits.data ? t('import.limits', { max: limits.data.maxMegabytes }) : t('import.formats')}</p>
              <input
                ref={input}
                type="file"
                multiple
                hidden
                accept={accept}
                aria-label={t('import.files')}
                onChange={(event) => {
                  add(event.target.files);
                  event.target.value = '';
                }}
              />
            </div>

            <label className="check">
              <input type="checkbox" checked={restricted} disabled={!spaceId} onChange={(event) => setRestricted(event.target.checked)} />
              {t('import.restrict')}
            </label>
            {restricted && spaceId && (
              <fieldset className="plain">
                <legend>{t('import.people')}</legend>
                {members.isError && <Callout tone="error" role="alert">{errorMessage(i18n, members.error)}</Callout>}
                {members.isPending && <div role="status"><Spinner /><span className="sr-only">{t('common.loading')}</span></div>}
                {members.data && (
                  <div className="people-pick">
                    {members.data.map((member) => {
                      // The maintainers of the space and the person who imports always see the documents.
                      const always = member.role === 'MAINTAINER' || member.self;
                      return (
                        <label key={member.id}>
                          <input type="checkbox" checked={always || chosen.has(member.id)} disabled={always} onChange={() => toggle(member.id)} />
                          <Avatar name={member.displayName} seed={member.id} />
                          <span className="list-main">
                            <span className="list-title">{member.displayName}</span>
                            <span className="list-sub">{member.email}</span>
                          </span>
                          {member.self
                            ? <span className="note">{t('import.you')}</span>
                            : member.role === 'MAINTAINER' && <span className="note">{t('access.reason.maintainer')}</span>}
                        </label>
                      );
                    })}
                  </div>
                )}
                <p className="help">{t('import.restrictHelp')}</p>
              </fieldset>
            )}

            {lines.length > 0 && (
              <section aria-label={i18n.tp('import.dropped', lines.length)}>
                <div className="row-between">
                  <strong style={{ fontSize: 13.5 }}>{i18n.tp('import.dropped', lines.length)}</strong>
                  <span className="help">{t('import.droppedHelp')}</span>
                </div>
                <ul className="files">
                  {lines.map((line) => <FileLine key={line.key} line={line} queue={queue} />)}
                </ul>
              </section>
            )}
          </>
        )}
        <div className="sr-only" role="log" aria-label={t('import.news')}>
          {news.map((item) => <p key={item.id}>{t('import.newsLine', { file: item.line.file.name, state: tellable(i18n, item.line) })}</p>)}
        </div>
      </Modal>

      {(confirming || blocked) && sending && (
        <Modal
          title={t('import.stopTitle')}
          onClose={keepSending}
          footer={(
            <>
              <button type="button" className="btn" data-autofocus onClick={keepSending}>{t('import.keepSending')}</button>
              <button type="button" className="btn btn-danger" onClick={stop}>{t('import.stop')}</button>
            </>
          )}
        >
          <p>{i18n.tp('import.stopBody', left)}</p>
        </Modal>
      )}
    </>
  );
}

/** The field error on the file, a 413 or a 507, as a check made before sending would give it. */
function problemOf(error: ApiError | undefined): Problem | null {
  if (!error) return null;
  if (error.status === 413) {
    const max = error.number('maxMegabytes');
    return { code: 'file_too_large', params: max === undefined ? {} : { max } };
  }
  if (error.status === 507) return { code: 'storage_full' };
  const field = error.fieldErrors.find((problem) => problem.field === 'file');
  return field ? { code: field.code, params: field.params } : null;
}

/** "Non importé : le fichier est vide." */
function refusal(i18n: I18n, line: Line): string {
  const { t } = i18n;
  const problem = line.problem ?? problemOf(line.error);
  const code = problem?.code;
  if (code === 'file_too_large') {
    // The answer may not give the limit: the one of the organization, shown in the window.
    const max = problem?.params?.max ?? line.maxMegabytes;
    return max === undefined
      ? t('import.refused.tooLarge')
      : t('import.refused.file_too_large', { size: sizeText(i18n, line.file.size), max });
  }
  if (code === 'unsupported_format') {
    return t('import.refused.unsupported_format', { formats: extensionList(String(problem?.params?.formats ?? '')) });
  }
  if (code === 'empty_file' || code === 'legacy_word' || code === 'content_mismatch' || code === 'storage_full') {
    return t(`import.refused.${code}`);
  }
  const fieldError = line.error?.fieldErrors[0];
  return t('import.refused.other', { reason: fieldError ? fieldMessage(i18n, fieldError) : errorMessage(i18n, line.error) });
}

/** The state of a file, as the window shows it. */
interface Described {
  tone: string;
  icon: IconName | 'busy';
  text: string;
  /** What to do after a failure that only a new file fixes. */
  fix?: string | null;
}

function describe(i18n: I18n, line: Line): Described | null {
  const { t } = i18n;
  const document = line.document;
  switch (line.state) {
    case 'refused': return { tone: 'is-err', icon: 'alert', text: refusal(i18n, line) };
    case 'waiting': return { tone: 'is-busy', icon: 'clock', text: t('import.waiting') };
    case 'sending': return { tone: 'is-busy', icon: 'busy', text: t('import.sending', { percent: line.percent }) };
    case 'interrupted': return { tone: 'is-err', icon: 'alert', text: t('import.interrupted') };
    case 'removed': return { tone: 'is-busy', icon: 'check', text: t('import.duplicateRemoved') };
    case 'gone': return { tone: 'is-busy', icon: 'info', text: t('import.gone') };
  }
  if (!document) return null;
  if (document.extractionStatus === 'PENDING') return { tone: 'is-busy', icon: 'busy', text: t('import.extracting') };
  if (document.extractionStatus === 'SCANNED') return { tone: 'is-warn', icon: 'scan', text: t('import.scanned') };
  if (document.extractionStatus === 'FAILED') {
    return {
      tone: 'is-err',
      icon: 'alert',
      text: t('failure.line', { cause: failureCause(i18n, document.extractionError) }),
      fix: failureFix(i18n, document.extractionError),
    };
  }
  return { tone: 'is-ok', icon: 'check', text: indexedText(i18n, document) };
}

/** What a screen reader hears of a file: its state, the duplicate, the failure of an action. */
function tellable(i18n: I18n, line: Line): string {
  const state = describe(i18n, line);
  const parts = state ? [state.text, state.fix] : [];
  if (line.state === 'imported' && line.duplicateOf) parts.push(i18n.t('import.duplicate', { title: line.duplicateOf.title }));
  if (line.actionError !== undefined) parts.push(errorMessage(i18n, line.actionError));
  return parts.filter(Boolean).join(' ');
}

/** The state of a file, with its icon; "busy" shows one that turns. */
function State({ tone, icon, children }: { tone: string; icon: IconName | 'busy'; children: ReactNode }) {
  return (
    <span className={`file-state ${tone}`}>
      {icon === 'busy' ? <Spinner /> : <Icon name={icon} />}
      <span>{children}</span>
    </span>
  );
}

/** One file: its name and size, then where it is, with what can be done. */
function FileLine({ line, queue }: { line: Line; queue: ImportQueue }) {
  const i18n = useI18n();
  const { t } = i18n;
  const file = line.file.name;
  const state = describe(i18n, line);
  // The name of the file goes into the name of each button: the list holds several of them.
  const action = (label: string, name: string, onClick: () => void) => (
    <> <button type="button" className="link" aria-label={name} disabled={line.busy} onClick={onClick}>{label}</button></>
  );

  let actions: ReactNode = null;
  if (line.state === 'interrupted' || (line.state === 'refused' && !line.problem && (line.error?.status ?? 0) >= 500)) {
    actions = action(t('common.retry'), t('import.retryFile', { file }), () => queue.retry(line.key));
  } else if (line.state === 'imported' && line.document?.extractionStatus === 'FAILED') {
    actions = isPassingFailure(line.document.extractionError)
      ? action(t('extraction.retry'), t('import.retryExtractionOf', { file }), () => void queue.retryExtraction(line.key))
      : state?.fix && <> {state.fix}</>;
  }

  const duplicate = line.state === 'imported' && line.duplicateOf;
  return (
    <li className="file">
      <Icon name={fileIcon(file)} />
      <span className="file-name">{file}</span>
      <span className="file-size">{sizeText(i18n, line.file.size)}</span>
      {state && <State tone={state.tone} icon={state.icon}>{state.text}{actions}</State>}
      {line.state === 'sending' && (
        <span className="progress" aria-hidden="true"><span style={{ width: `${line.percent}%` }} /></span>
      )}
      {duplicate && (
        <State tone="is-warn" icon="copy">
          {t('import.duplicate', { title: duplicate.title })}
          {line.canDelete && action(t('import.removeDuplicate'), t('import.removeDuplicateOf', { file }), () => void queue.removeDuplicate(line.key))}
        </State>
      )}
      {line.actionError !== undefined && <State tone="is-err" icon="alert">{errorMessage(i18n, line.actionError)}</State>}
    </li>
  );
}
