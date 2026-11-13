import { useMutation, useQuery } from '@tanstack/react-query';
import { api, call, type Schemas } from '../../api/client';
import { keys, queryClient } from '../../api/queryClient';
import { refreshSession } from '../../session/session';

export type Settings = Schemas['SettingsView'];
export type SettingsUpdate = Schemas['SettingsUpdate'];

export function useOrganizationSettings() {
  return useQuery({ queryKey: keys.organization, queryFn: () => call(() => api.GET('/api/v1/admin/organization')) });
}

/** Saves only the settings that changed; the server writes each change to the audit log. */
export function useSaveSettings(onSaved: (settings: Settings) => void, onRefused: (error: unknown) => void) {
  return useMutation({
    mutationFn: (changes: SettingsUpdate) => call(() => api.PATCH('/api/v1/admin/organization', { body: changes })),
    onSuccess: (settings) => {
      queryClient.setQueryData(keys.organization, settings);
      // The name, language and time zone of the organization are shown everywhere.
      void refreshSession();
      onSaved(settings);
    },
    onError: onRefused,
  });
}

/** The fields whose value differs from the saved settings. */
export function changesOf<T extends object>(saved: T, edited: Partial<T>): Partial<T> {
  const changes: Partial<T> = {};
  for (const [key, value] of Object.entries(edited) as [keyof T, T[keyof T]][]) {
    if (value !== saved[key]) changes[key] = value;
  }
  return changes;
}
