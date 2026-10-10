import type { ReactNode } from 'react';

const LABEL_RE = /^(项目|产品|商机|财务|业务|提效|品质)[:：]\s*(.*)$/;

type ReportBlock =
  | { type: 'label'; text: string }
  | { type: 'labeled'; label: string; text: string }
  | { type: 'list'; items: string[] }
  | { type: 'p'; text: string };

type MinutesBlock =
  | { type: 'h1' | 'h2'; text: string }
  | { type: 'p'; text: string; meta?: boolean }
  | { type: 'list'; items: string[] }
  | { type: 'table'; rows: string[][] };

function rich(text: string): ReactNode {
  const parts = text.split(/(\*\*[^*]+\*\*)/g).filter((part) => part.length > 0);
  if (parts.length === 1 && !parts[0].startsWith('**')) return text;
  return parts.map((part, index) =>
    part.startsWith('**') && part.endsWith('**') && part.length > 4 ? (
      <strong key={index}>{part.slice(2, -2)}</strong>
    ) : (
      part
    ),
  );
}

function decodeCell(value: string) {
  return value
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')
    .trim();
}

export function parseReport(value: string): ReportBlock[] {
  const blocks: ReportBlock[] = [];
  let items: string[] | null = null;
  const flush = () => {
    if (items?.length) blocks.push({ type: 'list', items });
    items = null;
  };
  for (const raw of value.replace(/\r\n/g, '\n').split('\n')) {
    const line = raw.trim();
    if (!line) {
      flush();
      continue;
    }
    const bullet = line.match(/^[-*]\s+(.*)$/);
    if (bullet) {
      items = items ?? [];
      items.push(bullet[1]);
      continue;
    }
    flush();
    const labeled = line.match(LABEL_RE);
    if (labeled) {
      if (labeled[2]) {
        blocks.push({ type: 'labeled', label: labeled[1], text: labeled[2] });
      } else {
        blocks.push({ type: 'label', text: labeled[1] });
      }
      continue;
    }
    blocks.push({ type: 'p', text: line });
  }
  flush();
  return blocks;
}

export function parseMinutes(value: string): MinutesBlock[] {
  const tables: string[][][] = [];
  const source = value.replace(/\r\n/g, '\n').replace(/<table\b[^>]*>[\s\S]*?<\/table>/gi, (html) => {
    const rows = [...html.matchAll(/<tr\b[^>]*>([\s\S]*?)<\/tr>/gi)]
      .map((row) =>
        [...row[1].matchAll(/<t[dh]\b[^>]*>([\s\S]*?)<\/t[dh]>/gi)].map((cell) =>
          decodeCell(cell[1]),
        ),
      )
      .filter((row) => row.some((cell) => cell));
    const index = tables.length;
    tables.push(rows);
    return `\n@@TABLE${index}@@\n`;
  });
  const blocks: MinutesBlock[] = [];
  let items: string[] | null = null;
  const flush = () => {
    if (items?.length) blocks.push({ type: 'list', items });
    items = null;
  };
  for (const raw of source.split('\n')) {
    const line = raw.trim();
    if (!line) {
      flush();
      continue;
    }
    const table = line.match(/^@@TABLE(\d+)@@$/);
    if (table) {
      flush();
      const rows = tables[Number(table[1])] || [];
      if (rows.length) blocks.push({ type: 'table', rows });
      continue;
    }
    const heading = line.match(/^(#{1,3})\s+(.+)$/);
    if (heading) {
      flush();
      blocks.push({
        type: heading[1].length === 1 ? 'h1' : 'h2',
        text: heading[2].replace(/\s+#+$/, ''),
      });
      continue;
    }
    const bullet = line.match(/^[-*]\s+(.*)$/);
    if (bullet) {
      items = items ?? [];
      items.push(bullet[1]);
      continue;
    }
    flush();
    blocks.push({
      type: 'p',
      text: line,
      meta: line.includes('会议周期'),
    });
  }
  flush();
  return blocks;
}

export function ReportBody({
  value,
  empty,
}: {
  value?: string;
  empty?: ReactNode;
}) {
  const blocks = parseReport(value || '');
  if (!blocks.length) return <>{empty}</>;
  return (
    <div className="sw-wr-doc">
      {blocks.map((block, index) => {
        if (block.type === 'label') {
          return (
            <div className="sw-wr-doc-label" key={index}>
              {block.text}
            </div>
          );
        }
        if (block.type === 'list') {
          return (
            <ul key={index}>
              {block.items.map((item, itemIndex) => (
                <li key={itemIndex}>{rich(item)}</li>
              ))}
            </ul>
          );
        }
        if (block.type === 'labeled') {
          return (
            <p key={index}>
              {[
                <span className="sw-wr-doc-key" key="label">{`${block.label}：`}</span>,
                <span key="text">{rich(block.text)}</span>,
              ]}
            </p>
          );
        }
        return <p key={index}>{rich(block.text)}</p>;
      })}
    </div>
  );
}

export function MinutesBody({
  value,
  empty,
}: {
  value?: string;
  empty?: ReactNode;
}) {
  const blocks = parseMinutes(value || '');
  if (!blocks.length) return <>{empty}</>;
  return (
    <div className="sw-wr-doc sw-wr-minutes-doc">
      {blocks.map((block, index) => {
        if (block.type === 'h1') {
          return (
            <h2 className="sw-wr-doc-title" key={index}>
              {block.text}
            </h2>
          );
        }
        if (block.type === 'h2') {
          return (
            <h3 className="sw-wr-doc-h" key={index}>
              {block.text}
            </h3>
          );
        }
        if (block.type === 'list') {
          return (
            <ul key={index}>
              {block.items.map((item, itemIndex) => (
                <li key={itemIndex}>{rich(item)}</li>
              ))}
            </ul>
          );
        }
        if (block.type === 'table') {
          const [head, ...body] = block.rows;
          return (
            <div className="sw-wr-doc-table-wrap" key={index}>
              <table>
                {body.length > 0 && (
                  <thead>
                    <tr>
                      {head.map((cell, cellIndex) => (
                        <th key={cellIndex}>{cell}</th>
                      ))}
                    </tr>
                  </thead>
                )}
                <tbody>
                  {(body.length > 0 ? body : block.rows).map((row, rowIndex) => (
                    <tr key={rowIndex}>
                      {row.map((cell, cellIndex) => (
                        <td key={cellIndex}>{rich(cell)}</td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          );
        }
        return (
          <p className={block.meta ? 'sw-wr-doc-meta' : undefined} key={index}>
            {rich(block.text)}
          </p>
        );
      })}
    </div>
  );
}
