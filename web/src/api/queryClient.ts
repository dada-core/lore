import { QueryClient } from '@tanstack/react-query';
import { ApiError } from './client';

/** One client for the whole interface: the API answers are cached here. */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      refetchOnWindowFocus: false,
      // A refusal (4xx) is not retried; a server or network failure is retried once.
      retry: (failures, error) => failures < 1 && !(error instanceof ApiError && error.status >= 400 && error.status < 500),
    },
    mutations: { retry: false },
  },
});

export const keys = {
  session: ['session'] as const,
  setup: ['setup'] as const,
  account: ['account'] as const,
  sessions: ['account', 'sessions'] as const,
  organization: ['admin', 'organization'] as const,
  members: (q: string, page: number) => ['admin', 'members', q, page] as const,
  allMembers: ['admin', 'members'] as const,
  invitations: ['admin', 'invitations'] as const,
};
