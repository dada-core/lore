// The words searched, marked in a part of the page: without regard to accents or case, as the
// search of Lore finds them. Each match becomes a <mark>, which screen readers can announce.

const MARK_CLASS = 'found-mark';

/** The text without accents and in lower case, and for each of its characters the index in the text. */
function fold(text: string): { folded: string; origin: number[] } {
  let folded = '';
  const origin: number[] = [];
  for (let i = 0; i < text.length; i += 1) {
    const plain = text[i].normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
    for (let k = 0; k < plain.length; k += 1) {
      folded += plain[k];
      origin.push(i);
    }
  }
  return { folded, origin };
}

/** The ranges of a text node where one of the terms appears, without overlap, in order. */
function matches(text: string, terms: string[]): [number, number][] {
  const { folded, origin } = fold(text);
  const found: [number, number][] = [];
  for (const term of terms) {
    const needle = fold(term).folded;
    if (!needle) continue;
    let from = folded.indexOf(needle);
    while (from >= 0) {
      const start = origin[from];
      const end = origin[from + needle.length - 1] + 1;
      found.push([start, end]);
      from = folded.indexOf(needle, from + needle.length);
    }
  }
  found.sort((a, b) => a[0] - b[0] || b[1] - a[1]);
  const kept: [number, number][] = [];
  for (const range of found) {
    if (kept.length === 0 || range[0] >= kept[kept.length - 1][1]) kept.push(range);
  }
  return kept;
}

/** Marks the terms in the element; returns the marks, in the order of the text. */
export function highlight(root: Element, terms: string[]): HTMLElement[] {
  if (terms.length === 0) return [];
  const walker = root.ownerDocument.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode: (node) => (node.parentElement?.closest(`button, .${MARK_CLASS}`) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT),
  });
  const nodes: Text[] = [];
  for (let node = walker.nextNode(); node; node = walker.nextNode()) nodes.push(node as Text);
  const marks: HTMLElement[] = [];
  for (const node of nodes) {
    const ranges = matches(node.data, terms);
    // From the end, so that the earlier positions stay right.
    const created: HTMLElement[] = [];
    for (let i = ranges.length - 1; i >= 0; i -= 1) {
      const [start, end] = ranges[i];
      const middle = node.splitText(start);
      middle.splitText(end - start);
      const mark = root.ownerDocument.createElement('mark');
      mark.className = MARK_CLASS;
      middle.parentNode?.replaceChild(mark, middle);
      mark.appendChild(middle);
      created.unshift(mark);
    }
    marks.push(...created);
  }
  return marks;
}

/** Removes the marks, the text staying as it was. */
export function clearHighlights(root: Element): void {
  for (const mark of [...root.querySelectorAll(`mark.${MARK_CLASS}`)]) {
    const parent = mark.parentNode;
    if (!parent) continue;
    while (mark.firstChild) parent.insertBefore(mark.firstChild, mark);
    parent.removeChild(mark);
    parent.normalize();
  }
}
