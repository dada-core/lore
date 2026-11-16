import { useMutation, useQuery } from '@tanstack/react-query';
import { useRef, useState, type FormEvent, type ReactNode } from 'react';
import { api, call, type Schemas } from '../../api/client';
import { keys, queryClient } from '../../api/queryClient';
import { extensionList, sizeText } from '../../documents/documents';
import { errorMessage } from '../../i18n/errors';
import { useI18n } from '../../i18n/i18n';
import { SettingsView } from '../../layouts/SettingsView';
import { Callout, hasProblems, message, problemsOf, SettingRow, useFocusFirstProblem, validate, type Problem, type Problems } from '../../ui/forms';
import { Spinner } from '../../ui/Icon';
import { useNotify } from '../../ui/Toast';

type Storage = Schemas['StorageView'];

/**
 * Administration › Storage and import: what the organization stores, the maximum size of a file
 * (up to the ceiling of the server), the formats, the state of the extraction and where the files
 * are kept.
 */
export function StoragePage() {
  const i18n = useI18n();
  const storage = useQuery({ queryKey: keys.storage, queryFn: () => call(() => api.GET('/api/v1/admin/storage')) });
  return (
    <SettingsView section="storage" title={i18n.t('admin.storage')}>
      {storage.data ? (
        <StorageSettings storage={storage.data} />
      ) : storage.isError ? (
        <div className="set-sec"><Callout tone="error" role="alert">{errorMessage(i18n, storage.error)}</Callout></div>
      ) : (
        <div className="set-sec" role="status"><Spinner /><span className="sr-only">{i18n.t('common.loading')}</span></div>
      )}
    </SettingsView>
  );
}

/** A line that only shows a value. */
function Row({ label, help, children }: { label: string; help?: ReactNode; children: ReactNode }) {
  return (
    <div className="set-row">
      <div className="lbl">
        <strong>{label}</strong>
        {help && <p className="help">{help}</p>}
      </div>
      <div className="ctl">{children}</div>
    </div>
  );
}

function range(value: string, min: number, max: number): Problem | null {
  const number = Number(value);
  if (!value.trim()) return { code: 'required' };
  return Number.isInteger(number) && number >= min && number <= max ? null : { code: 'out_of_range', params: { min, max } };
}

function StorageSettings({ storage }: { storage: Storage }) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const [size, setSize] = useState(String(storage.maxUploadMegabytes));
  const [problems, setProblems] = useState<Problems>({});
  const form = useRef<HTMLFormElement>(null);
  useFocusFirstProblem(form, problems);
  const ceiling = storage.maxUploadCeilingMegabytes;
  const number = (value: number) => <strong className="stat">{new Intl.NumberFormat(i18n.locale).format(value)}</strong>;

  const save = useMutation({
    mutationFn: (megabytes: number) => call(() => api.PATCH('/api/v1/admin/storage', { body: { maxUploadMegabytes: megabytes } })),
    onSuccess: (saved) => {
      queryClient.setQueryData(keys.storage, saved);
      // The import window checks files against the new size.
      void queryClient.invalidateQueries({ queryKey: keys.importLimits });
      setSize(String(saved.maxUploadMegabytes));
      notify(t('common.saved'));
    },
    onError: (error) => setProblems(problemsOf(error)),
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = validate({ maxUploadMegabytes: range(size, 1, ceiling) });
    setProblems(found);
    if (hasProblems(found)) return;
    save.mutate(Number(size));
  };

  const extraction = storage.extraction;
  const tika = !extraction.active
    ? <span className="pill p-gray">{t('storage.off')}</span>
    : extraction.problem
      ? <span className="pill p-red">{t('storage.broken')}</span>
      : <span className="pill p-green">{t('storage.active')}</span>;
  const tikaHelp = !extraction.active
    ? t('storage.offHelp')
    : extraction.problem
      ? t('storage.problem', { problem: extraction.problem })
      : `${t('storage.tikaHelp')} ${i18n.tp('storage.workers', extraction.workers)}`;

  return (
    <>
      <section className="set-sec" aria-labelledby="storage-usage">
        <h2 id="storage-usage">{t('storage.usage')}</h2>
        <div className="set-box">
          <Row label={t('storage.used')} help={t('storage.usedHelp')}><strong className="stat">{sizeText(i18n, storage.usedBytes)}</strong></Row>
          <Row label={t('storage.free')} help={t('storage.freeHelp')}><strong className="stat">{sizeText(i18n, storage.freeBytes)}</strong></Row>
          <Row label={t('storage.documents')}>{number(storage.documents)}</Row>
          <Row label={t('storage.scanned')} help={t('storage.scannedHelp')}>{number(storage.scanned)}</Row>
          <Row label={t('storage.failed')}>{number(storage.failed)}</Row>
          <Row label={t('storage.inProgress')}>{number(storage.inProgress)}</Row>
        </div>
      </section>

      <form className="set-sec" ref={form} noValidate onSubmit={submit} aria-labelledby="storage-import">
        <h2 id="storage-import">{t('storage.import')}</h2>
        {save.isError && !hasProblems(problemsOf(save.error)) && (
          <div style={{ marginTop: 12 }}><Callout tone="error" role="alert">{errorMessage(i18n, save.error)}</Callout></div>
        )}
        <div className="set-box">
          <SettingRow
            label={t('storage.maxSize')}
            help={`${t('storage.maxSizeHelp', { default: storage.defaultMaxUploadMegabytes })} ${t('storage.ceiling', { max: ceiling })}`}
            error={message(i18n, problems.maxUploadMegabytes)}
          >
            {(ids) => (
              <div className="ctl-line">
                <input
                  className="input input-num"
                  type="number"
                  inputMode="numeric"
                  min={1}
                  max={ceiling}
                  value={size}
                  onChange={(event) => setSize(event.target.value)}
                  {...ids}
                />
                <span className="help">{t('storage.megabytes')}</span>
              </div>
            )}
          </SettingRow>
          <Row label={t('storage.formats')} help={extensionList(storage.extensions)}>{t('storage.formatsValue')}</Row>
        </div>
        <div className="set-actions">
          <button type="submit" className="btn btn-primary" disabled={save.isPending}>
            {save.isPending && <Spinner />}
            {t('common.save')}
          </button>
        </div>
      </form>

      <section className="set-sec" aria-labelledby="storage-extraction">
        <h2 id="storage-extraction">{t('storage.extraction')}</h2>
        <div className="set-box">
          <Row label={t('storage.tika')} help={tikaHelp}>{tika}</Row>
          <Row label={t('storage.external')} help={t('storage.externalHelp')}><span className="pill p-gray">{t('storage.afterV01')}</span></Row>
          <Row label={t('storage.ocr')} help={t('storage.ocrHelp')}><span className="pill p-gray">{t('storage.unavailable')}</span></Row>
        </div>
      </section>

      <section className="set-sec" aria-labelledby="storage-files">
        <h2 id="storage-files">{t('storage.files')}</h2>
        <div className="set-box">
          <Row label={t('storage.location')} help={t('storage.locationHelp')}><code className="path">{storage.filesPath}</code></Row>
          <Row label={t('storage.s3')}><span className="pill p-gray">{t('storage.later')}</span></Row>
        </div>
      </section>
    </>
  );
}
