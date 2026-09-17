import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  join(dirname(fileURLToPath(import.meta.url)), 'index.tsx'),
  'utf8',
);

describe('key-matter project requirement', () => {
  it('requires a project when creating or editing a matter', () => {
    expect(source).toMatch(
      /name="projectId"[\s\S]*?label="关联项目"[\s\S]*?rules=\{\[\{ required: true/,
    );
    expect(source).not.toMatch(
      /<Form\.Item name="projectId" label="关联项目">\s*<Select\s+allowClear/,
    );
  });
});
