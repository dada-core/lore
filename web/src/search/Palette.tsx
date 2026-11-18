import { useQuery } from '@tanstack/react-query';
import { createContext, useContext, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { useNavigate } from 'react-router';
import { api, call } from '../api/client';
import { keys } from '../api/queryClient';
import { FormatIcon } from '../documents/documents';
import { useI18n } from '../i18n/i18n';
import { useUser } from '../session/session';
import { SpaceIcon, useSpaces } from '../spaces/spaces';
import { useDebounced } from '../ui/bits';
import { Icon, type IconName } from '../ui/Icon';
import { nextTheme, setTheme, useTheme } from '../ui/preferences';
import { useNotify } from '../ui/Toast';
import { Highlighted, searchAddress } from './search';

/** Opens the palette, from the sidebar, Ctrl K or the page of something not found. */
export const PaletteContext = createContext<() => void>(() => {});

export function useOpenPalette(): () => void {
  return useContext(PaletteContext);
}

interface Item {
  key: string;
  group: string;
  label: string;
  icon?: IconName;
  hint?: string;
  run: () => void;
  render?: () => ReactNode;
}

const MAX_ITEMS = 12;
/** The words typed, without accents or capitals, for the actions, destinations and spaces. */
const plain = (text: string) => text.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase();

/**
 * The palette: a document, a space or an action, from the keyboard. Empty, it offers the actions,
 * the destinations, the spaces and the recent documents; as soon as something is typed, first of
 * all to search the documents for it, then what matches, the titles being asked to the server among
 * the documents the person may read.
 */
export function Palette({ onClose, canImport, onImport, onCreateSpace }: {
  onClose: () => void;
  canImport: boolean;
  onImport: () => void;
  onCreateSpace: () => void;
}) {
  const i18n = useI18n();
  const { t } = i18n;
  const navigate = useNavigate();
  const notify = useNotify();
  const user = useUser();
  const theme = useTheme();
  const spaces = useSpaces();
  const ref = useRef<HTMLDialogElement>(null);
  const listId = useId();
  const [text, setText] = useState('');
  const [selected, setSelected] = useState(0);
  const typed = text.trim();
  const asked = useDebounced(typed, 150);
  const [opener] = useState(() => (document.activeElement instanceof HTMLElement ? document.activeElement : null));

  useEffect(() => {
    const dialog = ref.current;
    if (dialog && !dialog.open) dialog.showModal();
    dialog?.querySelector<HTMLInputElement>('input')?.focus();
    return () => opener?.focus();
  }, [opener]);

  const recent = useQuery({
    queryKey: [...keys.recentDocuments, 5],
    queryFn: () => call(() => api.GET('/api/v1/documents/recent', { params: { query: { size: 5 } } })),
    enabled: typed === '',
  });
  // The titles, from the server: the previous question is cancelled when a new one is asked.
  const titles = useQuery({
    queryKey: ['search', 'titles', asked],
    queryFn: ({ signal }) => call(() => api.GET('/api/v1/search/titles', { params: { query: { q: asked, size: 8 } }, signal })),
    enabled: asked !== '' && asked.length <= 100,
    staleTime: 30_000,
    retry: false,
  });

  const items = useMemo<Item[]>(() => {
    const go = (to: string) => () => navigate(to);
    const actions: Item[] = [
      ...(canImport ? [{ key: 'import', group: t('palette.actions'), label: t('library.import'), icon: 'upload' as const, hint: 'I', run: onImport }] : []),
      { key: 'create-space', group: t('palette.actions'), label: t('nav.createSpace'), icon: 'plus', run: onCreateSpace },
      {
        key: 'theme', group: t('palette.actions'), label: t('theme.change'), icon: 'theme',
        run: () => {
          const next = nextTheme(theme);
          setTheme(next);
          notify(t(`theme.now.${next}`));
        },
      },
    ];
    const places: Item[] = [
      { key: 'home', group: t('palette.goTo'), label: t('nav.home'), icon: 'home', run: go('/') },
      { key: 'search', group: t('palette.goTo'), label: t('nav.search'), icon: 'search', hint: '/', run: go('/search') },
      { key: 'spaces', group: t('palette.goTo'), label: t('nav.allSpaces'), icon: 'grid', run: go('/spaces') },
      ...(user.isAdmin ? [
        { key: 'admin', group: t('palette.goTo'), label: t('nav.admin'), icon: 'settings' as const, run: go('/admin') },
        { key: 'audit', group: t('palette.goTo'), label: t('admin.audit'), icon: 'clock' as const, run: go('/admin/audit') },
      ] : []),
      { key: 'account', group: t('palette.goTo'), label: t('nav.account'), icon: 'user', run: go('/account') },
    ];
    const spaceItems: Item[] = (spaces.data ?? []).map((space) => ({
      key: `space-${space.id}`, group: t('palette.spaces'), label: space.name,
      hint: i18n.tp('palette.documentCount', space.documentCount),
      run: go(`/spaces/${space.slug}`),
      render: () => <><SpaceIcon space={space} /><span>{space.name}</span></>,
    }));
    if (typed === '') {
      // Never an archived document here, even for who still sees it (decision 21 of the search).
      const recentItems: Item[] = (recent.data ?? []).filter((document) => document.status !== 'ARCHIVED').map((document) => ({
        key: `recent-${document.id}`, group: t('palette.recent'), label: document.title, hint: document.space.name,
        run: go(`/documents/${document.id}`),
        render: () => <><Icon name="file-text" /><span className="palette-label"><bdi>{document.title}</bdi></span></>,
      }));
      return [...actions, ...places, ...spaceItems, ...recentItems];
    }
    const words = plain(typed).split(/\s+/).filter(Boolean);
    const matches = (label: string) => words.every((word) => plain(label).includes(word));
    // Its own group: the titles found come last, under Documents, after what matches in between.
    const searchItem: Item = {
      key: 'search-documents', group: t('nav.search'), label: t('palette.searchFor', { query: typed }),
      icon: 'search', run: go(searchAddress(typed)),
    };
    const documents: Item[] = asked === typed ? (titles.data ?? []).map((document) => ({
      key: `document-${document.id}`, group: t('palette.documents'), label: document.title, hint: document.space.name,
      run: go(`/documents/${document.id}`),
      render: () => (
        <>
          <FormatIcon mediaType={document.mediaType} />
          <span className="palette-label"><bdi><Highlighted text={document.title} ranges={document.titleHighlights} /></bdi></span>
        </>
      ),
    })) : [];
    return [searchItem, ...[...actions, ...places, ...spaceItems].filter((item) => matches(item.label)), ...documents].slice(0, MAX_ITEMS);
  }, [typed, asked, titles.data, recent.data, spaces.data, canImport, onImport, onCreateSpace, theme, user.isAdmin, navigate, notify, t, i18n]);

  // What is typed changes the list: the first item is the one Enter opens.
  const [listOf, setListOf] = useState(typed);
  if (listOf !== typed) {
    setListOf(typed);
    setSelected(0);
  }
  const current = Math.min(selected, Math.max(items.length - 1, 0));

  const run = (item: Item | undefined) => {
    if (!item) return;
    onClose();
    item.run();
  };

  const onKey = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      if (items.length === 0) return;
      const step = event.key === 'ArrowDown' ? 1 : -1;
      setSelected((current + step + items.length) % items.length);
    } else if (event.key === 'Enter') {
      event.preventDefault();
      run(items[current]);
    }
  };

  useEffect(() => {
    ref.current?.querySelector(`[data-index="${current}"]`)?.scrollIntoView?.({ block: 'nearest' });
  }, [current]);

  // The items by group, in order: a listbox holds groups, each named by its heading.
  const groups: { name: string; items: { item: Item; index: number }[] }[] = [];
  items.forEach((item, index) => {
    const group = groups[groups.length - 1];
    if (group && group.name === item.group) group.items.push({ item, index });
    else groups.push({ name: item.group, items: [{ item, index }] });
  });
  return (
    <dialog
      ref={ref}
      className="palette"
      aria-label={t('palette.label')}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div className="palette-input">
        <Icon name="search" />
        <input
          value={text}
          onChange={(event) => setText(event.target.value)}
          onKeyDown={onKey}
          placeholder={t('palette.placeholder')}
          aria-label={t('palette.label')}
          role="combobox"
          aria-expanded="true"
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={items.length ? `${listId}-${current}` : undefined}
          autoComplete="off"
          maxLength={500}
        />
      </div>
      <div className="palette-list" id={listId} role="listbox" aria-label={t('palette.label')}>
        {items.length === 0 && <div className="palette-empty">{t('palette.empty')}</div>}
        {groups.map((group, g) => (
          <div key={`${g}-${group.name}`} role="group" aria-labelledby={`${listId}-g${g}`}>
            <div className="palette-group" id={`${listId}-g${g}`} role="presentation">{group.name}</div>
            {group.items.map(({ item, index }) => (
              <div
                key={item.key}
                id={`${listId}-${index}`}
                data-index={index}
                role="option"
                aria-selected={index === current}
                className="palette-item"
                onMouseMove={() => index !== current && setSelected(index)}
                onClick={() => run(item)}
              >
                {item.render ? item.render() : <>{item.icon && <Icon name={item.icon} />}<span className="palette-label">{item.label}</span></>}
                {item.hint && <span className="hint"><bdi>{item.hint}</bdi></span>}
              </div>
            ))}
          </div>
        ))}
      </div>
      <div className="sr-only" aria-live="polite">{i18n.tp('palette.results', items.length)}</div>
      <div className="palette-foot" aria-hidden="true">
        <span><kbd>↑</kbd><kbd>↓</kbd>{t('palette.navigate')}</span>
        <span><kbd>{t('palette.enter')}</kbd>{t('palette.open')}</span>
        <span><kbd>{t('palette.escape')}</kbd>{t('palette.close')}</span>
      </div>
    </dialog>
  );
}
