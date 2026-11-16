import { useMutation } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { api, call, type Schemas } from '../../api/client';
import { keys, queryClient } from '../../api/queryClient';
import { errorMessage } from '../../i18n/errors';
import { useI18n } from '../../i18n/i18n';
import { CopyField } from '../../ui/CopyField';
import { SPACE_ROLES, SpaceIcon, useSpaces, type SpaceRole } from '../../spaces/spaces';
import { Callout, checks, message, problemsOf, SelectField, TextField, validate, type Problems } from '../../ui/forms';
import { Icon, Spinner } from '../../ui/Icon';
import { Modal } from '../../ui/Modal';

/** Confirmation of an action that changes someone's access: says what will happen. */
export function ConfirmDialog({ title, body, confirm, onConfirm, onClose, pending, error }: {
  title: string;
  body: string;
  confirm: string;
  onConfirm: () => void;
  onClose: () => void;
  pending: boolean;
  error: unknown;
}) {
  const i18n = useI18n();
  return (
    <Modal
      title={title}
      onClose={onClose}
      onSubmit={(event) => {
        event.preventDefault();
        onConfirm();
      }}
      footer={(
        <>
          <button type="button" className="btn" onClick={onClose} data-autofocus>{i18n.t('common.cancel')}</button>
          <button type="submit" className="btn btn-danger" disabled={pending}>
            {pending && <Spinner />}
            {confirm}
          </button>
        </>
      )}
    >
      {error ? <Callout tone="error" role="alert">{errorMessage(i18n, error)}</Callout> : null}
      <p>{body}</p>
    </Modal>
  );
}

/** The reset link, shown once: the server keeps only its hash. */
export function ResetLinkDialog({ name, link, onClose }: { name: string; link: Schemas['CreatedLink']; onClose: () => void }) {
  const { t } = useI18n();
  return (
    <Modal
      title={t('members.resetTitle')}
      onClose={onClose}
      footer={<button type="button" className="btn btn-primary" onClick={onClose}>{t('common.close')}</button>}
    >
      <p>{t('members.resetBody', { name })}</p>
      <CopyField label={t('members.resetTitle')} value={link.url} help={t('members.resetHelp')} />
    </Modal>
  );
}

/**
 * An invitation link to the organization, passed on by the administrator: no mail server needed.
 * The optional address reserves the link and shows it among the pending invitations; the spaces
 * chosen are joined on arrival, each with its role.
 */
export function InviteDialog({ canInviteAdmins, onClose }: { canInviteAdmins: boolean; onClose: () => void }) {
  const i18n = useI18n();
  const { t } = i18n;
  const [role, setRole] = useState('MEMBER');
  const [email, setEmail] = useState('');
  const [spaces, setSpaces] = useState<Record<string, SpaceRole>>({});
  const [problems, setProblems] = useState<Problems>({});
  const [created, setCreated] = useState<Schemas['CreatedInvitation'] | null>(null);
  const available = useSpaces();

  const create = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/admin/invitations', {
      body: {
        role,
        email: email.trim() || null,
        spaces: Object.entries(spaces).map(([spaceId, spaceRole]) => ({ spaceId, role: spaceRole })),
      },
    })),
    onSuccess: (invitation) => {
      setCreated(invitation);
      void queryClient.invalidateQueries({ queryKey: keys.invitations });
    },
    onError: (error) => setProblems(problemsOf(error)),
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = validate({ email: email.trim() ? checks.email(email) : null });
    setProblems(found);
    if (Object.keys(found).length === 0) create.mutate();
  };

  const another = () => {
    setCreated(null);
    setEmail('');
    setSpaces({});
    setProblems({});
    create.reset();
  };

  if (created) {
    return (
      <Modal
        title={t('invite.title')}
        onClose={onClose}
        footer={(
          <>
            <button type="button" className="btn" onClick={another}><Icon name="plus" />{t('invite.another')}</button>
            <button type="button" className="btn btn-primary" onClick={onClose}>{t('common.close')}</button>
          </>
        )}
      >
        <p className="help">{t('invite.lead')}</p>
        <CopyField label={t('invite.link')} value={created.url} help={t('invite.linkHelp', { date: i18n.date(created.invitation.expiresAt) })} />
      </Modal>
    );
  }

  return (
    <Modal
      title={t('invite.title')}
      onClose={onClose}
      onSubmit={submit}
      footer={(
        <>
          <button type="button" className="btn" onClick={onClose}>{t('common.cancel')}</button>
          <button type="submit" className="btn btn-primary" disabled={create.isPending}>
            {create.isPending ? <Spinner /> : <Icon name="link" />}
            {t('invite.create')}
          </button>
        </>
      )}
    >
      <p className="help">{t('invite.lead')}</p>
      {create.isError && Object.keys(problemsOf(create.error)).length === 0 && (
        <Callout tone="error" role="alert">{errorMessage(i18n, create.error)}</Callout>
      )}
      <SelectField label={t('invite.role')} value={role} onChange={(event) => setRole(event.target.value)} error={message(i18n, problems.role)}>
        <option value="MEMBER">{t('role.MEMBER')}</option>
        {canInviteAdmins && <option value="ADMIN">{t('role.ADMIN')}</option>}
      </SelectField>
      <TextField
        label={`${t('invite.email')} (${t('common.optional')})`}
        type="email"
        autoComplete="off"
        value={email}
        onChange={(event) => setEmail(event.target.value)}
        help={t('invite.emailHelp')}
        error={message(i18n, problems.email)}
      />
      {(available.data ?? []).length > 0 && (
        <div className="field">
          <span className="field-label" id="invite-spaces">{`${t('invite.spaces')} (${t('common.optional')})`}</span>
          <div className="people-pick" role="group" aria-labelledby="invite-spaces">
            {(available.data ?? []).map((space) => (
              // The role list stays outside the label: a click on it must not untick the space.
              <div key={space.id} className="pick-row">
                <label>
                  <input
                    type="checkbox"
                    checked={space.id in spaces}
                    onChange={() => setSpaces((current) => {
                      const next = { ...current };
                      if (space.id in next) delete next[space.id];
                      else next[space.id] = 'CONTRIBUTOR';
                      return next;
                    })}
                  />
                  <SpaceIcon space={space} />
                  {space.name}
                </label>
                {space.id in spaces && (
                  <span className="role">
                    <select
                      className="select select-sm"
                      aria-label={t('invite.spaceRoleOf', { name: space.name })}
                      value={spaces[space.id]}
                      onChange={(event) => setSpaces((current) => ({ ...current, [space.id]: event.target.value as SpaceRole }))}
                    >
                      {SPACE_ROLES.map((value) => <option key={value} value={value}>{t(`spaceRole.${value}`)}</option>)}
                    </select>
                  </span>
                )}
              </div>
            ))}
          </div>
          {problems.spaces
            ? <p className="field-error" role="alert"><Icon name="alert" /><span>{message(i18n, problems.spaces)}</span></p>
            : <p className="help">{t('invite.spacesHelp')}</p>}
        </div>
      )}
    </Modal>
  );
}
