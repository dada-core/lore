import { useQuery } from '@tanstack/react-query';
import { useOutletContext, useSearchParams } from 'react-router';
import { api, call, type Schemas } from '../api/client';
import { keys, queryClient } from '../api/queryClient';
import { useI18n } from '../i18n/i18n';

export type Space = Schemas['SpaceView'];
export type SpaceRole = 'READER' | 'CONTRIBUTOR' | 'MAINTAINER';
export const SPACE_ROLES: SpaceRole[] = ['READER', 'CONTRIBUTOR', 'MAINTAINER'];

/** The colours offered for a space, the same list as the server's, with their names. */
export const SPACE_COLOURS = [
  ['#13705E', 'colour.fir'],
  ['#3F6C9A', 'colour.steel'],
  ['#8A6A1F', 'colour.ochre'],
  ['#6B5A8E', 'colour.violet'],
  ['#8E4B44', 'colour.brick'],
  ['#4E7A5A', 'colour.sage'],
  ['#5B5FD6', 'colour.indigo'],
] as const;

const ROLE_PILLS: Record<SpaceRole, string> = { READER: 'p-gray', CONTRIBUTOR: 'p-blue', MAINTAINER: 'p-purple' };
const SLUG = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

/** A space by its address; a space the person cannot see answers 404. */
export function spaceBySlug(slug: string) {
  return {
    queryKey: keys.space(slug),
    queryFn: () => call(() => api.GET('/api/v1/spaces/by-slug/{slug}', { params: { path: { slug } } })),
  };
}

/** The spaces the person sees, active or archived, by name. */
export function useSpaces(archived = false) {
  return useQuery({
    queryKey: keys.spaces(archived),
    queryFn: () => call(() => api.GET('/api/v1/spaces', { params: { query: { archived } } })),
  });
}

/**
 * After a change: the lists of spaces (sidebar, home page, counts) and the opened space are
 * loaded again. Its members and folders are refreshed by the screens that change them.
 */
export async function refreshSpaces(): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: keys.spaceLists }),
    queryClient.invalidateQueries({ queryKey: keys.spaceBySlug }),
  ]);
}

/** The space of the current address, loaded by SpaceRoute. */
export function useSpace(): Space {
  return useOutletContext<Space>();
}

/** Opens the "Create a space" window, wherever the person is. */
export function useCreateSpace(): () => void {
  const [params, setParams] = useSearchParams();
  return () => {
    // Already open: a second step in the history would leave a window that "Cancel" cannot close.
    if (params.get('open') === 'create-space') return;
    const next = new URLSearchParams(params);
    next.set('open', 'create-space');
    // Marked as opened from here: closing it goes back, without leaving a step in the history.
    setParams(next, { state: { createSpace: true } });
  };
}

/** "Réseau & Sécurité" gives "reseau-securite", as the server does. */
export function slugify(name: string): string {
  const slug = name.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return slug.length > 60 ? slug.slice(0, 60).replace(/-+$/, '') : slug;
}

export function isSlug(value: string): boolean {
  return value.length <= 60 && SLUG.test(value);
}

/** The address of a space, as shown under the field that sets it. */
export function spaceUrl(slug: string): string {
  return `${window.location.origin}/spaces/${slug || '…'}`;
}

export function canContribute(space: Space): boolean {
  return space.role !== 'READER' && !space.archivedAt;
}

export function canManage(space: Space): boolean {
  return space.role === 'MAINTAINER';
}

/** The coloured square of a space, with its initial. */
export function SpaceIcon({ space, large }: { space: { name: string; color: string }; large?: boolean }) {
  return (
    <span className={large ? 'space-ico space-ico-lg' : 'space-ico'} style={{ ['--c' as string]: space.color }} aria-hidden="true">
      {space.name.trim().charAt(0).toUpperCase()}
    </span>
  );
}

export function spaceRoleClass(role: string): string {
  return ROLE_PILLS[role as SpaceRole] ?? 'p-gray';
}

export function SpaceRolePill({ role }: { role: string }) {
  const { t } = useI18n();
  return <span className={`pill ${spaceRoleClass(role)}`}>{t(`spaceRole.${role as SpaceRole}`)}</span>;
}

/** The seven colours, as radio buttons. */
export function ColourPicker({ label, value, onChange }: { label: string; value: string; onChange: (colour: string) => void }) {
  const { t } = useI18n();
  return (
    <fieldset className="field colour-field">
      <legend className="field-label">{label}</legend>
      <div className="colour-pick">
        {SPACE_COLOURS.map(([colour, name]) => (
          <label key={colour} title={t(name)}>
            <input className="sr-only" type="radio" name="space-colour" value={colour} checked={value === colour} onChange={() => onChange(colour)} />
            <span className="colour-swatch" style={{ ['--c' as string]: colour }} aria-hidden="true" />
            <span className="sr-only">{t(name)}</span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}
