import { useQuery } from '@tanstack/react-query';
import { createContext, useContext } from 'react';
import { api, call, type CurrentUser, type Role, type SessionInfo } from '../api/client';
import { keys, queryClient } from '../api/queryClient';

/** Why the person no longer has a session: the sign-in page says it. */
export type SignOutReason = 'signedOut' | 'expired';
let signOutReason: SignOutReason | null = null;

export function lastSignOutReason(): SignOutReason | null {
  return signOutReason;
}

export function forgetSignOutReason(): void {
  signOutReason = null;
}

export function useSessionQuery() {
  return useQuery({
    queryKey: keys.session,
    queryFn: () => call(() => api.GET('/api/v1/session')),
    staleTime: Infinity,
    retry: 1,
  });
}

/** After sign-in, setup, joining or a password reset: the server answers with the new session. */
export function startSession(info: SessionInfo): void {
  signOutReason = null;
  queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== 'session' });
  queryClient.setQueryData(keys.session, info);
}

function endSession(reason: SignOutReason): void {
  signOutReason = reason;
  queryClient.setQueryData<SessionInfo>(keys.session, (info) => (info ? { ...info, user: null } : info));
  queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== 'session' });
}

/** The server answered 401: the session has expired, or was closed from another device. */
export function expireSession(): void {
  if (queryClient.getQueryData<SessionInfo>(keys.session)?.user) endSession('expired');
}

export async function signOut(): Promise<void> {
  try {
    await call(() => api.POST('/api/v1/auth/logout'));
  } finally {
    endSession('signedOut');
  }
}

/** Reloads the signed-in person, after a change of name, address or language. */
export function refreshSession(): Promise<void> {
  return queryClient.invalidateQueries({ queryKey: keys.session });
}

const SessionContext = createContext<SessionInfo | null>(null);
export const SessionProvider = SessionContext;

export function useSession(): SessionInfo {
  const session = useContext(SessionContext);
  if (!session) throw new Error('useSession outside SessionProvider');
  return session;
}

/** The signed-in person; only under RequireUser. */
export function useUser(): CurrentUser & { role: Role; isAdmin: boolean; isOwner: boolean } {
  const { user } = useSession();
  if (!user) throw new Error('useUser without a signed-in person');
  const role = user.role as Role;
  return { ...user, role, isAdmin: role === 'OWNER' || role === 'ADMIN', isOwner: role === 'OWNER' };
}

/**
 * The address to open after signing in: only a path of this site, never another site
 * ("//evil.invalid" or "/\evil.invalid" would leave it).
 */
export function safeNext(value: string | null | undefined): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) return '/';
  if (value.startsWith('/login') || value.startsWith('/setup')) return '/';
  return value;
}
