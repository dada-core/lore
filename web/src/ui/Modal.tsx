import { useEffect, useId, useRef, type FormEvent, type ReactNode } from 'react';
import { useI18n } from '../i18n/i18n';
import { Icon } from './Icon';

interface ModalProps {
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
  /** Makes the window a form: Enter submits it. */
  onSubmit?: (event: FormEvent<HTMLFormElement>) => void;
}

/**
 * A modal window, on the native dialog element: the page behind is inert, Escape closes it, and
 * the focus returns where it was. Mount it to open it, unmount it to close it.
 */
export function Modal({ title, onClose, children, footer, onSubmit }: ModalProps) {
  const { t } = useI18n();
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();

  useEffect(() => {
    const dialog = ref.current;
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    if (dialog && !dialog.open) dialog.showModal();
    return () => previous?.focus();
  }, []);

  const content = (
    <>
      <div className="modal-head">
        <h2 id={titleId}>{title}</h2>
        <button type="button" className="icon-btn" onClick={onClose} aria-label={t('common.close')}>
          <Icon name="x" />
        </button>
      </div>
      <div className="modal-body">{children}</div>
      {footer && <div className="modal-foot">{footer}</div>}
    </>
  );

  return (
    <dialog
      ref={ref}
      className="modal"
      aria-labelledby={titleId}
      onClose={onClose}
      // A click on the dimmed backdrop lands on the dialog element itself.
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      {onSubmit ? (
        <form className="modal-inner" noValidate onSubmit={onSubmit}>{content}</form>
      ) : (
        <div className="modal-inner">{content}</div>
      )}
    </dialog>
  );
}
