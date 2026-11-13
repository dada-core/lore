import type { ReactNode } from 'react';
import { Navigate, useLocation, useSearchParams } from 'react-router';
import { NotFoundPage } from '../pages/NotFoundPage';
import { lastSignOutReason, safeNext, useSession, useUser } from './session';

/**
 * The screens of a signed-in person. Without a session, the sign-in page opens, then comes back to
 * the requested address; after a deliberate sign-out, it opens without that detour.
 */
export function RequireUser({ children }: { children: ReactNode }) {
  const { user } = useSession();
  const location = useLocation();
  if (user) return children;
  const reason = lastSignOutReason();
  const requested = location.pathname + location.search;
  const next = reason === 'signedOut' || requested === '/' ? '' : `?next=${encodeURIComponent(requested)}`;
  return <Navigate to={`/login${next}`} replace state={{ notice: reason }} />;
}

/** Sign-in and forgotten password: nothing to do there once signed in. */
export function GuestOnly({ children }: { children: ReactNode }) {
  const { user } = useSession();
  const [params] = useSearchParams();
  if (user) return <Navigate to={safeNext(params.get('next'))} replace />;
  return children;
}

/** Administration: for anyone else, the same page as an address that does not exist. */
export function RequireAdmin({ children }: { children: ReactNode }) {
  const user = useUser();
  return user.isAdmin ? children : <NotFoundPage />;
}
