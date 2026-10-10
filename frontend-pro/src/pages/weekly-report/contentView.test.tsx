import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { MinutesBody, ReportBody } from './contentView';

describe('weekly report content view', () => {
  it('turns report labels and bullets into a readable outline', () => {
    render(
      <ReportBody
        value={'项目：\n- 皇家对数完成\n产品：\n- 云鹿 60%'}
      />,
    );
    expect(screen.getByText('项目')).toBeInTheDocument();
    expect(screen.getByText('皇家对数完成').tagName).toBe('LI');
    expect(screen.getByText('产品')).toBeInTheDocument();
    expect(screen.queryByText(/^-/)).not.toBeInTheDocument();
  });

  it('keeps a same-line label readable as one sentence', () => {
    render(<ReportBody value="项目：皇家积分切换完成对账" />);
    expect(screen.getByText('项目：')).toBeInTheDocument();
    expect(screen.getByText('皇家积分切换完成对账')).toBeInTheDocument();
  });

  it('renders minutes headings and the plan table without raw markup', () => {
    render(
      <MinutesBody
        value={[
          '# 电商业务BU周会会议纪要',
          '**会议周期：** 2026年09月14日 - 2026年09月18日',
          '',
          '## 皇家项目',
          '- 切换80%',
          '',
          '## 下周重点工作计划',
          '<table><tr><td>序号</td><td>工作项</td></tr><tr><td>1</td><td>对数</td></tr></table>',
        ].join('\n')}
      />,
    );
    expect(
      screen.getByRole('heading', { level: 2, name: '电商业务BU周会会议纪要' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 3, name: '皇家项目' })).toBeInTheDocument();
    expect(screen.getByText('切换80%').tagName).toBe('LI');
    expect(screen.getByRole('columnheader', { name: '工作项' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '对数' })).toBeInTheDocument();
    expect(document.body.textContent).not.toContain('<table');
    expect(document.body.textContent).not.toContain('##');
    expect(document.body.textContent).not.toContain('**');
  });
});
