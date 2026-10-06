import { describe, expect, it } from 'vitest';
import { shouldExposeApiDocs } from '../../apps/api/src/api-docs-policy';

describe('API documentation exposure', () => {
  it('keeps Swagger available only on development operational APIs', () => {
    expect(shouldExposeApiDocs('development', 'combined')).toBe(true);
    expect(shouldExposeApiDocs('development', 'operational')).toBe(true);
    expect(shouldExposeApiDocs('development', 'public')).toBe(false);
    expect(shouldExposeApiDocs('staging', 'operational')).toBe(false);
    expect(shouldExposeApiDocs('production', 'operational')).toBe(false);
  });
});
