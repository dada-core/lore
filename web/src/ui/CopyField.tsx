import { useId, useRef } from 'react';
import { useI18n } from '../i18n/i18n';
import { copyText } from './bits';
import { Icon } from './Icon';
import { useNotify } from './Toast';

/** A link shown once (invitation, password reset), with a button that copies it. */
export function CopyField({ label, value, help }: { label: string; value: string; help?: string }) {
  const { t } = useI18n();
  const notify = useNotify();
  const input = useRef<HTMLInputElement>(null);
  const id = useId();
  return (
    <div className="field">
      <label className="sr-only" htmlFor={id}>{label}</label>
      <div className="copy-row">
        <input
          ref={input}
          id={id}
          className="input input-mono"
          readOnly
          value={value}
          onFocus={(event) => event.currentTarget.select()}
          aria-describedby={help ? `${id}-help` : undefined}
        />
        <button
          type="button"
          className="btn"
          onClick={async () => notify(t((await copyText(value, input.current)) ? 'common.linkCopied' : 'common.copyFailed'))}
        >
          <Icon name="copy" />
          {t('common.copy')}
        </button>
      </div>
      {help && <p className="help" id={`${id}-help`}>{help}</p>}
    </div>
  );
}
