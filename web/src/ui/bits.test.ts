import { describe, expect, it } from 'vitest';
import { initials } from './bits';

describe('initials', () => {
  it('takes the first letter of the first two words', () => {
    expect(initials('Amenan Souaga')).toBe('AS');
    expect(initials('  Hervé   Coulibaly ')).toBe('HC');
  });

  it('splits a single word written in two parts', () => {
    expect(initials('DadaGroup')).toBe('DG');
    expect(initials('Eli')).toBe('E');
  });
});
