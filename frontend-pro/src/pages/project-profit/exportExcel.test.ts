import ExcelJS from 'exceljs';
import { describe, expect, it } from 'vitest';
import type { ProjectProfitRow } from '@/services/superwork/api';
import {
  buildProjectProfitWorkbook,
  exportWan,
  projectProfitExportFileName,
  type ProjectProfitExportSource,
} from './exportExcel';

const moneyRow = (partial: Partial<ProjectProfitRow>): ProjectProfitRow => ({
  rowType: 'PROJECT',
  category: '项目',
  projectId: 1,
  projectName: '皇家项目',
  editable: false,
  revenue: null,
  smsCost: null,
  directCost: null,
  platformFee: null,
  compensation: null,
  outsourcing: null,
  softwareGift: null,
  hours: null,
  cost: null,
  grossProfit: null,
  grossProfitRate: null,
  ...partial,
});

const source = (
  partial: Partial<ProjectProfitExportSource> & { row: ProjectProfitRow },
): ProjectProfitExportSource => ({
  block: { label: '全年', provisional: false },
  line: { businessLineName: '全渠道云鹿定制' },
  spans: { month: 1, line: 1, category: 1 },
  ...partial,
});

describe('project profit excel export', () => {
  it('names the file by year and view', () => {
    expect(projectProfitExportFileName(2026, 'YEAR')).toBe('项目利润-2026-全年.xlsx');
    expect(projectProfitExportFileName(2026, 'month')).toBe('项目利润-2026-月度.xlsx');
  });

  it('converts yuan to 万元 with the same rounding as the table', () => {
    expect(exportWan(260375.5)).toBe(26.04);
    expect(exportWan(-703890.18)).toBe(-70.39);
    expect(exportWan(0)).toBe(0);
    expect(exportWan(null)).toBeNull();
  });

  it('writes the visible rows, merges repeated labels, and keeps detail totals', async () => {
    const rows = [
      source({
        spans: { month: 2, line: 2, category: 1 },
        row: moneyRow({
          directCost: 200000,
          grossProfit: 76332.02,
          grossProfitRate: 22.98,
        }),
      }),
      source({
        spans: { month: 0, line: 0, category: 1 },
        row: moneyRow({
          rowType: 'TOTAL',
          category: null,
          projectId: null,
          projectName: '合计',
          directCost: 260375.5,
          outsourcing: 84239,
          softwareGift: 2013.6,
          cost: 1635935.71,
          grossProfit: 7659.18,
          grossProfitRate: 0.38,
        }),
      }),
    ];
    const workbook = new ExcelJS.Workbook();
    await workbook.xlsx.load(await buildProjectProfitWorkbook(rows));
    const sheet = workbook.getWorksheet('项目利润');
    expect(sheet?.getRow(1).getCell(7).value).toBe('直接成本(万元)');
    expect(sheet?.getRow(2).getCell(1).value).toBe('全年');
    expect(sheet?.getRow(2).getCell(7).value).toBe(20);
    expect(sheet?.getCell('A2').isMerged).toBe(true);
    expect(sheet?.getRow(3).getCell(4).value).toBe('合计');
    expect(sheet?.getRow(3).getCell(7).value).toBe(26.04);
    expect(sheet?.getRow(3).getCell(10).value).toBe(8.42);
    expect(sheet?.getRow(3).getCell(11).value).toBe(0.2);
    expect(sheet?.getRow(3).getCell(13).value).toBe(163.59);
    expect(sheet?.getRow(3).getCell(14).value).toBe(0.77);
    expect(sheet?.getRow(3).getCell(7).fill).toMatchObject({
      fgColor: { argb: 'FFEEF4FF' },
    });
  });

  it('marks provisional months', async () => {
    const workbook = new ExcelJS.Workbook();
    await workbook.xlsx.load(
      await buildProjectProfitWorkbook([
        source({
          block: { label: '9月', provisional: true },
          row: moneyRow({ projectName: '未分配', rowType: 'RESIDUAL', category: '差额' }),
        }),
      ]),
    );
    const sheet = workbook.getWorksheet('项目利润');
    expect(sheet?.getRow(2).getCell(1).value).toBe('9月（预估）');
    expect(sheet?.getRow(2).getCell(3).value).toBe('差额');
  });
});
