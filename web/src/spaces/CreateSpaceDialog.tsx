import { useMutation } from '@tanstack/react-query';
import { useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import { api, call } from '../api/client';
import { errorMessage } from '../i18n/errors';
import { useI18n } from '../i18n/i18n';
import { Callout, checks, hasProblems, message, problemsOf, TextField, validate, type Problems } from '../ui/forms';
import { Icon, Spinner } from '../ui/Icon';
import { Modal } from '../ui/Modal';
import { useNotify } from '../ui/Toast';
import { ColourPicker, isSlug, refreshSpaces, slugify, SPACE_COLOURS, spaceUrl, useSpaces } from './spaces';

/**
 * Create a space: any member of the organization can, and becomes its maintainer. The address
 * follows the name until it is changed by hand.
 */
export function CreateSpaceDialog({ onClose }: { onClose: () => void }) {
  const i18n = useI18n();
  const { t } = i18n;
  const navigate = useNavigate();
  const notify = useNotify();
  const existing = useSpaces();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [slug, setSlug] = useState<string | null>(null);
  const [chosenColour, setChosenColour] = useState<string | null>(null);
  // Closing may go back in the history: a second click before the window is gone must not go back twice.
  const closed = useRef(false);
  const close = () => {
    if (closed.current) return;
    closed.current = true;
    onClose();
  };
  const [problems, setProblems] = useState<Problems>({});
  const address = slug ?? slugify(name);
  // Each new space suggests the next colour; the one shown is the one sent.
  const colour = chosenColour ?? SPACE_COLOURS[(existing.data?.length ?? 0) % SPACE_COLOURS.length][0];

  const create = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/spaces', {
      // Unless it was changed by hand, the address is left to the server, which adds -2, -3... when it is taken.
      body: { name: name.trim(), description: description.trim() || null, slug: slug?.trim() || null, color: colour },
    })),
    onSuccess: (space) => {
      void refreshSpaces();
      notify(t('createSpace.created'));
      // Closed while the space was being created: the person stays on the page they went back to.
      if (closed.current) return;
      // The new space takes the place of the open window: "Back" does not open it again.
      navigate(`/spaces/${space.slug}`, { replace: true });
    },
    onError: (error) => setProblems(problemsOf(error)),
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = validate({
      name: checks.required(name, 80),
      description: description.trim().length > 500 ? { code: 'too_long', params: { max: 500 } } : null,
      slug: address && !isSlug(address) ? { code: 'invalid_slug' } : null,
    });
    setProblems(found);
    if (!hasProblems(found)) create.mutate();
  };

  return (
    <Modal
      title={t('createSpace.title')}
      onClose={close}
      onSubmit={submit}
      footer={(
        <>
          <button type="button" className="btn" onClick={close}>{t('common.cancel')}</button>
          <button type="submit" className="btn btn-primary" disabled={create.isPending}>
            {create.isPending ? <Spinner /> : <Icon name="plus" />}
            {t('createSpace.submit')}
          </button>
        </>
      )}
    >
      {create.isError && !hasProblems(problemsOf(create.error)) && (
        <Callout tone="error" role="alert">{errorMessage(i18n, create.error)}</Callout>
      )}
      <TextField
        label={t('space.name')}
        placeholder={t('createSpace.namePlaceholder')}
        value={name}
        data-autofocus
        onChange={(event) => setName(event.target.value)}
        error={message(i18n, problems.name)}
      />
      <TextField
        label={`${t('space.description')} (${t('common.optional')})`}
        value={description}
        onChange={(event) => setDescription(event.target.value)}
        error={message(i18n, problems.description)}
      />
      <TextField
        label={t('space.slug')}
        mono
        autoComplete="off"
        spellCheck={false}
        value={address}
        onChange={(event) => setSlug(event.target.value.toLowerCase())}
        help={spaceUrl(address)}
        error={message(i18n, problems.slug)}
      />
      <ColourPicker label={t('space.colour')} value={colour} onChange={setChosenColour} />
      <p className="help">{t('createSpace.lead')}</p>
    </Modal>
  );
}
