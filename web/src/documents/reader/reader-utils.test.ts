import { describe, expect, it } from 'vitest';
import { clearHighlights, highlight } from './highlight';
import { readTarget, sectionLink, termsOf } from './target';

describe('the place of the address', () => {
  it('reads the place, the version and where the link comes from', () => {
    expect(readTarget('?from=search&q=pg_restore&v=4', '#h-7-2')).toEqual({
      place: 'h-7', page: null, version: 4, query: 'pg_restore', fromSearch: true,
    });
    expect(readTarget('', '#page-112')).toMatchObject({ place: 'page-112', page: 112, version: null, fromSearch: false });
    expect(readTarget('', '#part-3')).toMatchObject({ place: 'part-3', page: null });
  });

  it('ignores what is not a place of Lore', () => {
    expect(readTarget('?v=abc', '#root').place).toBeNull();
    expect(readTarget('?v=-2', '#h-').version).toBeNull();
    expect(readTarget('', '#%E0%A4%A').place).toBeNull();
    expect(readTarget('', '').place).toBeNull();
  });

  it('cuts a query into words and quoted phrases', () => {
    expect(termsOf('pg_restore role "does not exist" a')).toEqual(['does not exist', 'pg_restore', 'role']);
  });

  it('leaves out the syntax of the search: or, and the words left out', () => {
    expect(termsOf('pg_restore or pg_dump -barman -"old way" kube-system --no-owner')).toEqual(['pg_restore', 'pg_dump', 'kube-system', '--no-owner']);
  });

  it('gives a section its version', () => {
    expect(sectionLink('d1', 3, 'h-7')).toBe(`${window.location.origin}/documents/d1?v=3#h-7`);
  });
});

describe('the words found, marked', () => {
  it('marks them without regard to accents or case, and leaves the text as it was once cleared', () => {
    const root = document.createElement('div');
    root.innerHTML = '<p>Le <strong>RÔLE</strong> lore n’existe pas : créez le rôle, puis relancez pg_restore.</p>';
    const before = root.innerHTML;

    const marks = highlight(root, ['role', 'PG_RESTORE']);

    expect(marks.map((mark) => mark.textContent)).toEqual(['RÔLE', 'rôle', 'pg_restore']);
    expect(root.textContent).toBe('Le RÔLE lore n’existe pas : créez le rôle, puis relancez pg_restore.');
    clearHighlights(root);
    expect(root.innerHTML).toBe(before);
  });

  it('finds a phrase, and never marks inside a button', () => {
    const root = document.createElement('div');
    root.innerHTML = '<h2>4.2 Erreurs fréquentes<button>#</button></h2><p>role "does not exist" ici</p>';

    expect(highlight(root, ['erreurs frequentes', '#']).map((mark) => mark.textContent)).toEqual(['Erreurs fréquentes']);
    expect(highlight(root, ['does not exist']).map((mark) => mark.textContent)).toEqual(['does not exist']);
  });
});
