import ExcelJS from 'exceljs';
import type { ProjectProfitRow } from '@/services/superwork/api';

export type ProjectProfitExportView = 'month' | 'H1' | 'H2' | 'YEAR';

/** 与页面表格同一套平铺行，导出时只读展示字段。 */
export interface ProjectProfitExportSource {
  block: { label: string; provisional?: boolean | null };
  line: { businessLineName: string };
  row: ProjectProfitRow;
  spans: { month: number; line: number; category: number };
}

const VIEW_LABEL: Record<ProjectProfitExportView, string> = {
  month: '月度',
  H1: 'H1',
  H2: 'H2',
  YEAR: '全年',
};

const MONEY_FIELDS = [
  ['revenue', '营业收入(万元)'],
  ['smsCost', '短信成本(万元)'],
  ['directCost', '直接成本(万元)'],
  ['platformFee', '平台佣金&手续费(万元)'],
  ['compensation', '赔付(万元)'],
  ['outsourcing', '协力&外包(万元)'],
  ['softwareGift', '软件赠送(万元)'],
] as const;

const HEADERS = [
  '月份',
  '业务线',
  '分类',
  '项目',
  ...MONEY_FIELDS.map(([, title]) => title),
  '工时(人月)',
  '成本(万元)',
  '考核毛利(万元)',
  '考核毛利率(%)',
];

const ROW_FILL: Record<string, string> = {
  TOTAL: 'FFEEF4FF',
  SUMMARY: 'FFD9E6FF',
  RESIDUAL: 'FFFFF7E6',
};

/** 与页面 formatWan 相同：元 ÷ 10000，保留两位。 */
export const exportWan = (value?: number | null) => {
  if (value == null) return null;
  return Math.round((Number(value) / 10000) * 100) / 100;
};

const exportHours = (value?: number | null) => {
  if (value == null) return null;
  return Math.round(Number(value) * 100) / 100;
};

const exportRate = (value?: number | null) => {
  if (value == null) return null;
  return Math.round(Number(value) * 10) / 10;
};

export const projectProfitExportFileName = (year: number, view: ProjectProfitExportView) =>
  `项目利润-${year}-${VIEW_LABEL[view]}.xlsx`;

const monthText = (row: ProjectProfitExportSource) =>
  row.block.provisional ? `${row.block.label}（预估）` : row.block.label;

const textCell = (row: ProjectProfitExportSource, span: number, text: string) =>
  span > 0 ? text : '';

export async function buildProjectProfitWorkbook(rows: ProjectProfitExportSource[]) {
  const workbook = new ExcelJS.Workbook();
  workbook.creator = '项目利润';
  const sheet = workbook.addWorksheet('项目利润', {
    views: [{ state: 'frozen', ySplit: 1 }],
  });
  sheet.addRow(HEADERS);
  const header = sheet.getRow(1);
  header.font = { bold: true };
  header.alignment = { vertical: 'middle', horizontal: 'center', wrapText: true };
  header.height = 32;
  header.eachCell((cell) => {
    cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFF3F5F8' } };
    cell.border = thinBorder();
  });

  rows.forEach((source, index) => {
    const values: (string | number | null)[] = [
      textCell(source, source.spans.month, monthText(source)),
      textCell(source, source.spans.line, source.line.businessLineName),
      textCell(source, source.spans.category, source.row.category ?? '—'),
      source.row.projectName ?? '—',
      ...MONEY_FIELDS.map(([field]) => exportWan(source.row[field])),
      exportHours(source.row.hours),
      exportWan(source.row.cost),
      exportWan(source.row.grossProfit),
      exportRate(source.row.grossProfitRate),
    ];
    const excelRow = sheet.addRow(values);
    const fill = ROW_FILL[source.row.rowType];
    excelRow.eachCell({ includeEmpty: true }, (cell, col) => {
      cell.border = thinBorder();
      cell.alignment = { vertical: 'middle', horizontal: col <= 4 ? 'left' : 'right' };
      if (fill) {
        cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: fill } };
        cell.font = {
          bold: source.row.rowType === 'TOTAL' || source.row.rowType === 'SUMMARY',
          color: source.row.rowType === 'RESIDUAL' ? { argb: 'FF874D00' } : undefined,
        };
      }
      if (col >= 5 && col <= 14 && typeof cell.value === 'number') {
        cell.numFmt = '#,##0.00;[Red]-#,##0.00';
      }
      if (col === 15 && typeof cell.value === 'number') {
        cell.numFmt = '0.0';
      }
    });
  });
  rows.forEach((source, index) => {
    const excelIndex = index + 2;
    mergeDown(sheet, excelIndex, 1, source.spans.month);
    mergeDown(sheet, excelIndex, 2, source.spans.line);
    mergeDown(sheet, excelIndex, 3, source.spans.category);
  });

  [14, 22, 10, 16, ...HEADERS.slice(4).map(() => 16)].forEach((width, index) => {
    sheet.getColumn(index + 1).width = width;
  });
  sheet.autoFilter = { from: { row: 1, column: 1 }, to: { row: 1, column: HEADERS.length } };
  return workbook.xlsx.writeBuffer();
}

export async function downloadProjectProfitExcel(input: {
  year: number;
  view: ProjectProfitExportView;
  rows: ProjectProfitExportSource[];
}) {
  const buffer = await buildProjectProfitWorkbook(input.rows);
  const blob = new Blob([buffer], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = projectProfitExportFileName(input.year, input.view);
  link.click();
  URL.revokeObjectURL(url);
}

const mergeDown = (sheet: ExcelJS.Worksheet, row: number, column: number, span: number) => {
  if (span > 1) sheet.mergeCells(row, column, row + span - 1, column);
};

const thinBorder = (): ExcelJS.Borders => ({
  top: { style: 'thin', color: { argb: 'FFE5E6EB' } },
  left: { style: 'thin', color: { argb: 'FFE5E6EB' } },
  bottom: { style: 'thin', color: { argb: 'FFE5E6EB' } },
  right: { style: 'thin', color: { argb: 'FFE5E6EB' } },
  diagonal: {},
});
