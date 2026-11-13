import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import type { Role } from '../api/client';
import { useI18n } from '../i18n/i18n';

const AVATAR_COLOURS = ['#5B5FD6', '#13705E', '#3F6C9A', '#8A6A1F', '#6B5A8E', '#8E4B44', '#4E7A5A', '#3F5A73'];

/** "Amenan Souaga" gives "AS"; a single word written in two parts, "DadaGroup", gives "DG". */
export function initials(name: string): string {
  let words = name.trim().split(/\s+/);
  if (words.length === 1) words = words[0].split(/(?=\p{Lu})/u);
  return words.map((word) => word[0] ?? '').join('').slice(0, 2).toUpperCase();
}

function colourOf(seed: string): string {
  let hash = 0;
  for (const char of seed) hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
  return AVATAR_COLOURS[hash % AVATAR_COLOURS.length];
}

export function Avatar({ name, seed, large }: { name: string; seed?: string; large?: boolean }) {
  return (
    <span className={large ? 'avatar avatar-lg' : 'avatar'} style={{ ['--av' as string]: colourOf(seed ?? name) }} aria-hidden="true">
      {initials(name)}
    </span>
  );
}

const ROLE_COLOURS: Record<Role, string> = { OWNER: 'p-orange', ADMIN: 'p-purple', MEMBER: 'p-gray' };

export function RolePill({ role }: { role: string }) {
  const { t } = useI18n();
  const known = role === 'OWNER' || role === 'ADMIN' || role === 'MEMBER';
  return <span className={`pill ${known ? ROLE_COLOURS[role] : 'p-gray'}`}>{known ? t(`role.${role}`) : role}</span>;
}

export function roleClass(role: Role): string {
  return ROLE_COLOURS[role];
}

/** Sets the title of the browser tab: "Sign in – Lore". */
export function useDocumentTitle(title: string): void {
  useEffect(() => {
    document.title = title ? `${title} – Lore` : 'Lore';
  }, [title]);
}

/**
 * A horizontally scrolling area without a visible scrollbar: a fade on each side signals the
 * remaining content.
 */
export function HorizontalScroll({ children, stickyFirstColumn }: { children: ReactNode; stickyFirstColumn?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  const [hints, setHints] = useState({ left: false, right: false });

  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    const update = () => setHints({
      left: element.scrollLeft > 2,
      right: element.scrollLeft + element.clientWidth < element.scrollWidth - 2,
    });
    update();
    element.addEventListener('scroll', update, { passive: true });
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(update);
    observer?.observe(element);
    return () => {
      element.removeEventListener('scroll', update);
      observer?.disconnect();
    };
  }, []);

  const classes = ['hx', hints.left && 'hint-l', hints.right && 'hint-r', stickyFirstColumn && 'has-primary'].filter(Boolean).join(' ');
  return (
    <div className={classes}>
      {/* Focusable, so that the keyboard can scroll it. */}
      <div className="grid-wrap" ref={ref} tabIndex={hints.left || hints.right ? 0 : undefined}>{children}</div>
    </div>
  );
}

/** Vertical fade at the top and bottom of an area that scrolls without a visible scrollbar. */
export function useVerticalFade<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    const update = () => {
      element.classList.toggle('hint-t', element.scrollTop > 2);
      element.classList.toggle('hint-b', element.scrollTop + element.clientHeight < element.scrollHeight - 2);
    };
    update();
    element.addEventListener('scroll', update, { passive: true });
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(update);
    observer?.observe(element);
    for (const child of Array.from(element.children)) observer?.observe(child);
    return () => {
      element.removeEventListener('scroll', update);
      observer?.disconnect();
    };
  }, []);
  return ref;
}

/** Copies a text to the clipboard; on failure, selects the field so that it can be copied by hand. */
export async function copyText(text: string, field?: HTMLInputElement | null): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    field?.focus();
    field?.select();
    return false;
  }
}
