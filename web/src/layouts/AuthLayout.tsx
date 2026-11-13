import { useEffect, type ReactNode } from 'react';
import { useLocation } from 'react-router';
import { Logo } from '../ui/Icon';

/**
 * Access screens (setup, sign-in, invitation, password): a centred card under the logo. The
 * focus goes to the title of each new screen, and of each new step ({@code step}).
 */
export function AuthLayout({ wide, step, children }: { wide?: boolean; step?: number; children: ReactNode }) {
  const { pathname } = useLocation();
  useEffect(() => {
    const title = document.querySelector<HTMLElement>('.auth h1');
    if (title) {
      title.tabIndex = -1;
      title.focus({ preventScroll: true });
    }
  }, [pathname, step]);
  return (
    <main className="auth">
      <div className={wide ? 'auth-card auth-wide' : 'auth-card'}>
        <div className="auth-logo"><Logo size={36} /></div>
        {children}
      </div>
    </main>
  );
}
