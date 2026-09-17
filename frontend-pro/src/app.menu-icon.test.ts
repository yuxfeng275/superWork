import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  join(dirname(fileURLToPath(import.meta.url)), 'app.tsx'),
  'utf8',
);

describe('menu icons', () => {
  it('does not export mapServerMenu as a Umi runtime config', () => {
    expect(source).not.toMatch(/export const mapServerMenu/);
    expect(source).toMatch(
      /const mapServerMenu = \(nodes\?: MenuTreeNode\[\]\): MenuDataItem\[\] =>/,
    );
  });

  it('keeps home as a house and workbench as an app grid', () => {
    expect(source).toMatch(/home: <HomeOutlined \/>/);
    expect(source).toMatch(/workspace: <AppstoreOutlined \/>/);
    expect(source).toMatch(/工作台: menuGroupIcons\.workspace/);
    expect(source).toMatch(/name: '首页',\s*icon: menuGroupIcons\.home/);
    expect(source).toMatch(
      /menuGroupIconByName\[name\] \?\?\s*menuIconByServerName/,
    );
  });
});
