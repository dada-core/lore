import { describe, expect, it } from 'vitest';
import { safeNext } from './session';

describe('safeNext', () => {
  it('keeps a path of this site', () => {
    expect(safeNext('/admin/members?open=invite')).toBe('/admin/members?open=invite');
  });

  it('never leaves the site', () => {
    expect(safeNext('https://evil.invalid')).toBe('/');
    expect(safeNext('//evil.invalid')).toBe('/');
    expect(safeNext('/\\evil.invalid')).toBe('/');
    expect(safeNext(null)).toBe('/');
  });

  it('does not come back to the sign-in page', () => {
    expect(safeNext('/login?next=/account')).toBe('/');
  });
});
