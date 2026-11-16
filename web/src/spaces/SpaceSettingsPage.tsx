import { useMutation } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import { api, call } from '../api/client';
import { keys, queryClient } from '../api/queryClient';
import { errorMessage } from '../i18n/errors';
import { useI18n } from '../i18n/i18n';
import { View } from '../layouts/AppShell';
import { useDocumentTitle } from '../ui/bits';
import { Callout, checks, hasProblems, message, problemsOf, TextField, validate, type Problems } from '../ui/forms';
import { Icon, Spinner } from '../ui/Icon';
import { Modal } from '../ui/Modal';
import { useNotify } from '../ui/Toast';
import { ArchivedNotice } from './SpaceLibraryPage';
import { spaceCrumbs } from './SpaceRoute';
import { canManage, ColourPicker, isSlug, refreshSpaces, spaceUrl, useSpace, type Space } from './spaces';

/** The settings of a space, for its maintainers: name, description, address, colour, archiving, deletion. */
export function SpaceSettingsPage() {
  const i18n = useI18n();
  const { t } = i18n;
  const space = useSpace();
  useDocumentTitle(t('spaceSettings.title'));

  return (
    <View crumbs={spaceCrumbs(t('nav.spaces'), space, t('nav.spaceSettings'))}>
      <div className="pad">
        <div className="settings-inner">
          <h1 className="page-title">{t('spaceSettings.title')}</h1>
          {canManage(space) ? (
            <>
              {space.archivedAt && <ArchivedNotice space={space} />}
              <GeneralSettings key={space.id} space={space} />
              <DangerZone space={space} />
            </>
          ) : (
            <Callout tone="error" role="alert">{t('error.maintainer_only')}</Callout>
          )}
        </div>
      </div>
    </View>
  );
}

function GeneralSettings({ space }: { space: Space }) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const navigate = useNavigate();
  const [name, setName] = useState(space.name);
  const [description, setDescription] = useState(space.description ?? '');
  const [slug, setSlug] = useState(space.slug);
  const [colour, setColour] = useState(space.color);
  const [problems, setProblems] = useState<Problems>({});
  const archived = !!space.archivedAt;

  const save = useMutation({
    mutationFn: () => call(() => api.PATCH('/api/v1/spaces/{id}', {
      params: { path: { id: space.id } },
      body: { name: name.trim(), description: description.trim(), slug: slug.trim(), color: colour },
    })),
    onSuccess: (saved) => {
      setName(saved.name);
      setDescription(saved.description ?? '');
      setSlug(saved.slug);
      setColour(saved.color);
      notify(t('common.saved'));
      if (saved.slug !== space.slug) {
        // The new address is known already: no loading screen, and no request for the old one.
        queryClient.setQueryData(keys.space(saved.slug), saved);
        navigate(`/spaces/${saved.slug}/settings`, { replace: true });
        queryClient.removeQueries({ queryKey: keys.space(space.slug) });
      }
      void refreshSpaces();
    },
    onError: (error) => setProblems(problemsOf(error)),
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = validate({
      name: checks.required(name, 80),
      description: description.trim().length > 500 ? { code: 'too_long', params: { max: 500 } } : null,
      slug: !slug.trim() ? { code: 'required' } : isSlug(slug.trim()) ? null : { code: 'invalid_slug' },
    });
    setProblems(found);
    if (!hasProblems(found)) save.mutate();
  };

  return (
    <form className="set-sec" noValidate onSubmit={submit}>
      <h2>{t('spaceSettings.general')}</h2>
      {save.isError && !hasProblems(problemsOf(save.error)) && (
        <Callout tone="error" role="alert">{errorMessage(i18n, save.error)}</Callout>
      )}
      <fieldset className="set-box space-form" disabled={archived}>
        <TextField label={t('space.name')} value={name} onChange={(event) => setName(event.target.value)} error={message(i18n, problems.name)} />
        <TextField
          label={`${t('space.description')} (${t('common.optional')})`}
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          help={t('spaceSettings.descriptionHelp')}
          error={message(i18n, problems.description)}
        />
        <TextField
          label={t('space.slug')}
          mono
          autoComplete="off"
          spellCheck={false}
          value={slug}
          onChange={(event) => setSlug(event.target.value.toLowerCase())}
          help={`${spaceUrl(slug.trim())}. ${t('spaceSettings.slugHelp')}`}
          error={message(i18n, problems.slug)}
        />
        <ColourPicker label={t('space.colour')} value={colour} onChange={setColour} />
      </fieldset>
      {!archived && (
        <div style={{ marginTop: 12 }}>
          <button type="submit" className="btn btn-primary" disabled={save.isPending}>
            {save.isPending && <Spinner />}
            {t('common.save')}
          </button>
        </div>
      )}
    </form>
  );
}

function DangerZone({ space }: { space: Space }) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const [deleting, setDeleting] = useState(false);
  const archived = !!space.archivedAt;

  const archive = useMutation({
    mutationFn: () => archived
      ? call(() => api.POST('/api/v1/spaces/{id}/unarchive', { params: { path: { id: space.id } } }))
      : call(() => api.POST('/api/v1/spaces/{id}/archive', { params: { path: { id: space.id } } })),
    onSuccess: () => notify(t(archived ? 'spaceSettings.unarchived' : 'spaceSettings.archived')),
    onError: (error) => notify(errorMessage(i18n, error)),
    onSettled: () => void refreshSpaces(),
  });

  return (
    <section className="set-sec" aria-labelledby="space-danger">
      <h2 id="space-danger">{t('spaceSettings.danger')}</h2>
      <div className="set-box danger-box">
        <div className="set-row">
          <div className="lbl">
            <strong>{t(archived ? 'spaceSettings.unarchive' : 'spaceSettings.archive')}</strong>
            <p className="help">{t(archived ? 'spaceSettings.unarchiveHelp' : 'spaceSettings.archiveHelp')}</p>
          </div>
          <div className="ctl">
            <button type="button" className="btn" disabled={archive.isPending} onClick={() => archive.mutate()}>
              {archive.isPending ? <Spinner /> : <Icon name={archived ? 'refresh' : 'archive'} />}
              {t(archived ? 'spaceSettings.unarchiveButton' : 'spaceSettings.archiveButton')}
            </button>
          </div>
        </div>
        <div className="set-row">
          <div className="lbl">
            <strong>{t('spaceSettings.delete')}</strong>
            <p className="help">{t('spaceSettings.deleteHelp')}</p>
          </div>
          <div className="ctl">
            <button type="button" className="btn btn-danger" onClick={() => setDeleting(true)}>
              <Icon name="trash" />{t('spaceSettings.deleteButton')}
            </button>
          </div>
        </div>
      </div>
      {deleting && <DeleteSpaceDialog space={space} onClose={() => setDeleting(false)} />}
    </section>
  );
}

/** The deletion is final: it is confirmed by typing the name of the space. */
function DeleteSpaceDialog({ space, onClose }: { space: Space; onClose: () => void }) {
  const i18n = useI18n();
  const { t } = i18n;
  const notify = useNotify();
  const navigate = useNavigate();
  const [typed, setTyped] = useState('');
  const confirmed = typed.trim() === space.name;

  const remove = useMutation({
    mutationFn: () => call(() => api.DELETE('/api/v1/spaces/{id}', { params: { path: { id: space.id } } })),
    onSuccess: () => {
      queryClient.removeQueries({ queryKey: keys.space(space.slug) });
      void refreshSpaces();
      notify(t('deleteSpace.deleted', { name: space.name }));
      navigate('/spaces', { replace: true });
    },
  });

  return (
    <Modal
      title={t('deleteSpace.title')}
      onClose={onClose}
      onSubmit={(event) => {
        event.preventDefault();
        if (confirmed) remove.mutate();
      }}
      footer={(
        <>
          <button type="button" className="btn" onClick={onClose}>{t('common.cancel')}</button>
          <button type="submit" className="btn btn-danger" disabled={!confirmed || remove.isPending}>
            {remove.isPending && <Spinner />}
            {t('deleteSpace.submit')}
          </button>
        </>
      )}
    >
      {remove.isError && <Callout tone="error" role="alert">{errorMessage(i18n, remove.error)}</Callout>}
      <Callout tone="warn">{t('deleteSpace.warning', { name: space.name })}</Callout>
      <TextField
        label={t('deleteSpace.confirm', { name: space.name })}
        autoComplete="off"
        data-autofocus
        value={typed}
        onChange={(event) => setTyped(event.target.value)}
      />
    </Modal>
  );
}
