import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react';
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
 * the focus returns where it was. Mount it to open it, unmount it to close it. The element to
 * focus first, a field for example, carries the data-autofocus attribute.
 */
export function Modal({ title, onClose, children, footer, onSubmit }: ModalProps) {
  const { t } = useI18n();
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  // The element that opened the window, read before the window takes the focus: it gets it back on closing.
  const [opener] = useState(() => (document.activeElement instanceof HTMLElement ? document.activeElement : null));

  useEffect(() => {
    const dialog = ref.current;
    if (dialog && !dialog.open) dialog.showModal();
    // autoFocus does not work here: React applies it while the window is still closed, hence hidden.
    // The element to focus first carries data-autofocus; without it, the browser picks the first button.
    dialog?.querySelector<HTMLElement>('[data-autofocus]')?.focus();
    return () => opener?.focus();
  }, [opener]);

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
      // A click on the dimmed backdrop lands on the dialog element itself. The second click of a
      // double click on the button that opened the window lands there too: it does not close it.
      onClick={(event) => {
        if (event.target === event.currentTarget && event.detail < 2) onClose();
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
