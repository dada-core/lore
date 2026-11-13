import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';

const NotifyContext = createContext<(message: string) => void>(() => {});

/**
 * Brief confirmation messages ("Changes saved"), announced to screen readers and gone after three
 * seconds. The live region exists before any message, so that the first one is announced too.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<{ message: string; id: number } | null>(null);
  const timer = useRef<number | undefined>(undefined);

  const notify = useCallback((message: string) => {
    window.clearTimeout(timer.current);
    setToast({ message, id: Date.now() });
    timer.current = window.setTimeout(() => setToast(null), 3200);
  }, []);

  useEffect(() => () => window.clearTimeout(timer.current), []);

  return (
    <NotifyContext value={notify}>
      {children}
      <div role="status" aria-live="polite">
        {toast && <div className="toast" key={toast.id}>{toast.message}</div>}
      </div>
    </NotifyContext>
  );
}

export function useNotify(): (message: string) => void {
  return useContext(NotifyContext);
}
