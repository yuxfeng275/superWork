import { ThunderboltOutlined } from '@ant-design/icons';
import { Button, DatePicker, Modal, Tag, Typography } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useMemo, useState } from 'react';
import {
  superworkApi,
  type WeeklyReportStatus,
  type WeeklyReportVO,
} from '@/services/superwork/api';
import { statusOf, toMonday } from './shared';

interface Props {
  open: boolean;
  /** 已有周报列表（周历打标 + 已存在提示） */
  existing: WeeklyReportVO[];
  onClose: () => void;
  /** 创建/打开成功回调：generate=true 时由父级继续触发 AI 生成 */
  onCreated: (weekStart: string, generate: boolean) => void;
}

/**
 * 新建周报（方案甲）：月历式选周，已有周报的周按状态色打点；
 * 选中已有周 → 主按钮变「打开该周报」；新周 → 「创建」或「创建并立即生成」。
 */
export default function CreateReportModal({
  open,
  existing,
  onClose,
  onCreated,
}: Props) {
  const [week, setWeek] = useState<Dayjs>(dayjs());
  const [creating, setCreating] = useState(false);

  /** 周一日期 → 状态（月历打点 + 选中提示） */
  const weekStatusMap = useMemo(() => {
    const map = new Map<string, WeeklyReportStatus>();
    for (const row of existing) map.set(row.weekStartDate, row.status);
    return map;
  }, [existing]);

  /** 状态打点头颜色（与状态语义一致） */
  const dotColor: Record<WeeklyReportStatus, string> = {
    PENDING: '#8c8c8c',
    GENERATING: '#2563eb',
    DRAFT: '#d48806',
    CONFIRMED: '#16a34a',
    PUBLISHED: '#2563eb',
    GENERATION_FAILED: '#dc2626',
  };

  const selectedMonday = toMonday(week);
  const selectedStatus = weekStatusMap.get(selectedMonday);

  const submit = async (generate: boolean) => {
    setCreating(true);
    try {
      // getWeeklyReport 语义 = getOrCreate：已存在直接返回
      const report = await superworkApi.getWeeklyReport(selectedMonday);
      onCreated(report.weekStartDate, generate && !selectedStatus);
      onClose();
    } catch {
      /* 全局拦截器已提示 */
    } finally {
      setCreating(false);
    }
  };

  return (
    <Modal
      title="新建周报"
      open={open}
      onCancel={onClose}
      footer={
        <>
          <Button onClick={onClose}>取消</Button>
          {selectedStatus ? (
            <Button
              type="primary"
              loading={creating}
              onClick={() => void submit(false)}
            >
              打开该周报
            </Button>
          ) : (
            <>
              <Button loading={creating} onClick={() => void submit(false)}>
                仅创建
              </Button>
              <Button
                type="primary"
                icon={<ThunderboltOutlined />}
                loading={creating}
                onClick={() => void submit(true)}
              >
                创建并立即生成
              </Button>
            </>
          )}
        </>
      }
    >
      <div className="sw-create-row">
        <Typography.Text>选择周</Typography.Text>
        <DatePicker
          picker="week"
          value={week}
          onChange={(value) => value && setWeek(value)}
          allowClear={false}
          format="YYYY 第 ww 周"
          cellRender={(current, info) => {
            if (info.type !== 'date') return info.originNode;
            const status = weekStatusMap.get(toMonday(current as Dayjs));
            return (
              <div className="sw-week-cell">
                {info.originNode}
                {status && (
                  <i
                    className="sw-week-dot"
                    style={{ background: dotColor[status] }}
                  />
                )}
              </div>
            );
          }}
        />
      </div>
      <div className="sw-create-hint">
        {selectedStatus ? (
          <>
            该周已有周报：
            <Tag color={statusOf(selectedStatus).color}>
              {statusOf(selectedStatus).label}
            </Tag>
          </>
        ) : (
          <Typography.Text type="secondary">
            将创建 {selectedMonday} 所在周的周报；「创建并立即生成」会同时触发
            AI 草稿生成（约 30-60 秒）。
          </Typography.Text>
        )}
      </div>
    </Modal>
  );
}
