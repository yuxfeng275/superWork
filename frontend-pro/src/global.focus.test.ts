import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const css = readFileSync(
  join(dirname(fileURLToPath(import.meta.url)), 'global.less'),
  'utf8',
);

const extraOutlineBlock = css.match(
  /((?:[^{}/]|\/\*[\s\S]*?\*\/)+)\{\s*outline:\s*2px solid #60a5fa !important;[\s\S]*?\}/,
)?.[1];

describe('global form focus styles', () => {
  it('does not add an extra outline ring on mouse-focused inputs', () => {
    expect(extraOutlineBlock).toBeTruthy();
    expect(extraOutlineBlock).not.toMatch(/\.ant-input(?!-)/);
    expect(extraOutlineBlock).not.toContain('.ant-input-affix-wrapper');
    expect(extraOutlineBlock).not.toContain('.ant-select-focused');
    expect(extraOutlineBlock).not.toContain('.ant-picker-focused');
  });

  it('keeps keyboard focus rings for buttons and navigation', () => {
    expect(extraOutlineBlock).toContain('.ant-btn:focus-visible');
    expect(extraOutlineBlock).toContain('.ant-menu-item:focus-visible');
    expect(extraOutlineBlock).toContain('.ant-tabs-tab:focus-visible');
  });
});
