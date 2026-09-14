import {
  DownloadOutlined,
  EditOutlined,
  ReloadOutlined,
  SettingOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Col,
  Form,
  Input,
  InputNumber,
  Modal,
  message,
  Row,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { superworkApi } from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

const money = (v: unknown) =>
  typeof v === 'number' ? `¥${(v / 10000).toFixed(1)}万` : '—';
export default function KpiReportPage() {
  const currentYear = new Date().getFullYear();
  const [year, setYear] = useState(currentYear);
  const [report, setReport] = useState<Record<string, unknown>>({});
  const [targets, setTargets] = useState<Record<string, unknown>[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [targetOpen, setTargetOpen] = useState(false);
  const [editingTarget, setEditingTarget] = useState<Record<string, unknown>>();
  const [targetForm] = Form.useForm<Record<string, unknown>>();
  const [noteOpen, setNoteOpen] = useState(false);
  const [noteForm] = Form.useForm<Record<string, unknown>>();
  const [noteId, setNoteId] = useState<number>();
  const [rules, setRules] = useState<Record<string, unknown>[]>([]);
  const [worktime, setWorktime] = useState<Record<string, unknown>>({});
  const [syncLogs, setSyncLogs] = useState<Record<string, unknown>[]>([]);
  const [worktimeOpen, setWorktimeOpen] = useState(false);
  const [worktimeForm] = Form.useForm<Record<string, unknown>>();
  const [saving, setSaving] = useState(false);
  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [r, t, rs, w, logs] = await Promise.all([
        superworkApi.getKpiReport(year),
        superworkApi.getKpiTargets(year),
        superworkApi.getKpiAlertRules().catch(() => []),
        superworkApi.getWorktimeStatus().catch(() => ({})),
        superworkApi.getWorktimeSyncLogs().catch(() => []),
      ]);
      setReport(r);
      setTargets(t);
      setRules(rs);
      setWorktime(w);
      setSyncLogs(logs);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'KPI 报告加载失败');
    } finally {
      setLoading(false);
    }
  }, [year]);
  useEffect(() => {
    void load();
  }, [load]);
  const total = (report.total || {}) as Record<string, unknown>;
  const groups = (report.groups || []) as Record<string, unknown>[];
  const monthColumns = useMemo(() => {
    const months = new Set<number>();
    groups.forEach((group) => {
      (Array.isArray(group.snapshots) ? group.snapshots : []).forEach(
        (snapshot: Record<string, unknown>) => {
          const month = Number(String(snapshot.weekEndDate || '').slice(5, 7));
          if (month) months.add(month);
        },
      );
    });
    return Array.from(months).sort((a, b) => a - b);
  }, [groups]);
  const snapshotFor = (group: Record<string, unknown>, month: number) => {
    const snapshots = (
      Array.isArray(group.snapshots) ? group.snapshots : []
    ) as Record<string, unknown>[];
    const inMonth = snapshots.filter(
      (snapshot) =>
        Number(String(snapshot.weekEndDate || '').slice(5, 7)) === month,
    );
    return inMonth[inMonth.length - 1];
  };
  const noteFor = (
    group: Record<string, unknown>,
    month: number,
    metric: string,
  ) => {
    const snapshot = snapshotFor(group, month);
    const notes = (
      Array.isArray(snapshot?.notes) ? snapshot.notes : []
    ) as Record<string, unknown>[];
    const note = notes.find((item) => item.metric === metric);
    return note
      ? ({ ...note, snapshot } as Record<string, unknown>)
      : undefined;
  };
  const monthDelta = (
    group: Record<string, unknown>,
    month: number,
    field: 'ytdRevenue' | 'ytdProfit',
  ) => {
    const current = snapshotFor(group, month);
    if (!current) return undefined;
    const previous = snapshotFor(group, month - 1);
    if (!previous) return current[field];
    return Number(current[field] || 0) - Number(previous[field] || 0);
  };
  const kpiRows: Record<string, unknown>[] = groups.flatMap((group) => [
    { ...group, metric: 'revenue', metricLabel: '营收 YTD' },
    { ...group, metric: 'profit', metricLabel: '利润 YTD' },
    { ...group, metric: 'rate', metricLabel: '目标完成率' },
  ]);
  const run = async () => {
    try {
      await superworkApi.runKpiSnapshot();
      message.success('本周快照已生成');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '快照生成失败');
    }
  };
  const saveTarget = async (values: Record<string, unknown>) => {
    setSaving(true);
    try {
      await superworkApi.saveKpiTarget({
        year,
        reportGroup: String(values.reportGroup),
        revenueTarget: Number(values.revenueTarget),
        profitTarget: Number(values.profitTarget),
        remark: values.remark as string,
      });
      message.success('目标已保存');
      setTargetOpen(false);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '目标保存失败');
    } finally {
      setSaving(false);
    }
  };
  const syncWorktime = async (kind: 'contracts' | 'monthly') => {
    setSaving(true);
    try {
      if (kind === 'contracts') await superworkApi.syncWorktimeContracts(year);
      else await superworkApi.syncWorktimeMonthly();
      message.success('工时系统同步已发起');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '同步失败');
    } finally {
      setSaving(false);
    }
  };
  const saveRule = async (row: Record<string, unknown>) => {
    try {
      await superworkApi.saveKpiAlertRule({
        reportGroup: row.reportGroup || null,
        weeklyDivisor: Number(row.weeklyDivisor),
        yellowRatio: Number(row.yellowRatio),
        enabled: row.enabled ? 1 : 0,
      });
      message.success('预警规则已保存');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '规则保存失败');
    }
  };
  const openNote = (row: Record<string, unknown>) => {
    const id = Number(row.noteId || row.id || 0);
    if (!id) {
      message.info('该单元格暂无异常备注');
      return;
    }
    setNoteId(id);
    noteForm.setFieldsValue({
      deviationReason: row.deviationReason || '',
      isAbnormal: row.isAbnormal,
      countermeasure: row.countermeasure || '',
    });
    setNoteOpen(true);
  };
  const saveNote = async (values: Record<string, unknown>) => {
    if (!noteId) return;
    if (
      values.isAbnormal === 1 &&
      !String(values.countermeasure || '').trim()
    ) {
      message.warning('判定为异常时必须填写对策');
      return;
    }
    try {
      await superworkApi.saveKpiNote(
        noteId,
        values as {
          deviationReason: string;
          isAbnormal: number | null;
          countermeasure?: string;
        },
      );
      message.success('备注已保存');
      setNoteOpen(false);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '备注保存失败');
    }
  };
  const saveWorktime = async (values: Record<string, unknown>) => {
    setSaving(true);
    try {
      await superworkApi.saveWorktimeConfig(values);
      message.success('工时系统配置已保存');
      setWorktimeOpen(false);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '配置保存失败');
    } finally {
      setSaving(false);
    }
  };
  return (
    <div className="sw-page sw-kpi">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            DATA / KPI WEEKLY
          </Typography.Text>
          <Typography.Title level={2}>KPI 周报</Typography.Title>
          <Typography.Paragraph type="secondary">
            按年度查看营收、利润快照与目标偏差，支持生成快照、导出与工时系统同步。
          </Typography.Paragraph>
        </div>
        <Space>
          <Select
            value={year}
            style={{ width: 110 }}
            options={[currentYear - 1, currentYear, currentYear + 1].map(
              (value) => ({
                label: `${value}年`,
                value,
              }),
            )}
            onChange={setYear}
          />
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          <Button
            icon={<DownloadOutlined />}
            onClick={() =>
              void superworkApi
                .downloadKpiReport(year)
                .then(() => message.success('KPI 报告已下载'))
                .catch((e) =>
                  message.error(e instanceof Error ? e.message : '导出失败'),
                )
            }
          >
            导出 Excel
          </Button>
          <Button
            icon={<ThunderboltOutlined />}
            type="primary"
            onClick={() => void run()}
          >
            生成本周快照
          </Button>
        </Space>
      </div>
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取 KPI 报告"
          description={error}
        />
      )}
      <Row gutter={14}>
        {[
          ['营收目标', total.revenueTarget],
          ['累计营收', total.ytdRevenue],
          ['利润目标', total.profitTarget],
          ['累计利润', total.ytdProfit],
        ].map(([label, value]) => (
          <Col span={6} key={String(label)}>
            <Card variant="borderless">
              <Typography.Text type="secondary">
                {String(label)}
              </Typography.Text>
              <Typography.Title level={3}>{money(value)}</Typography.Title>
            </Card>
          </Col>
        ))}
      </Row>
      <Tabs
        className="sw-kpi-tabs"
        items={[
          {
            key: 'report',
            label: '分组快照',
            children: (
              <Card variant="borderless">
                <Table
                  loading={loading}
                  rowKey={(r) =>
                    `${String(
                      (r as Record<string, unknown>).reportGroup || 'unknown',
                    )}-${String((r as Record<string, unknown>).metric || '')}`
                  }
                  dataSource={kpiRows}
                  scroll={{ x: Math.max(980, 260 + monthColumns.length * 220) }}
                  columns={[
                    {
                      title: '报告组',
                      dataIndex: 'reportGroup',
                      fixed: 'left',
                      width: 130,
                    },
                    {
                      title: '指标',
                      dataIndex: 'metricLabel',
                      fixed: 'left',
                      width: 120,
                    },
                    ...monthColumns.map((month) => ({
                      title: `${month}月`,
                      children: [
                        {
                          title: 'YTD',
                          key: `${month}-ytd`,
                          render: (
                            _: unknown,
                            row: Record<string, unknown>,
                          ) => {
                            const snapshot = snapshotFor(row, month);
                            if (!snapshot) return '—';
                            if (row.metric === 'rate')
                              return `${(
                                Number(snapshot.revenueRate || 0) * 100
                              ).toFixed(1)}% / ${(
                                Number(snapshot.profitRate || 0) * 100
                              ).toFixed(1)}%`;
                            return money(
                              row.metric === 'revenue'
                                ? snapshot.ytdRevenue
                                : snapshot.ytdProfit,
                            );
                          },
                        },
                        {
                          title: '环比',
                          key: `${month}-delta`,
                          render: (
                            _: unknown,
                            row: Record<string, unknown>,
                          ) => {
                            if (row.metric === 'rate') return '—';
                            const metric =
                              row.metric === 'revenue' ? 'revenue' : 'profit';
                            const delta = monthDelta(
                              row,
                              month,
                              row.metric === 'revenue'
                                ? 'ytdRevenue'
                                : 'ytdProfit',
                            );
                            const note = noteFor(row, month, metric);
                            if (delta === undefined) return '—';
                            return (
                              <Button
                                type="link"
                                className={`sw-kpi-delta ${
                                  note?.alertLevel || ''
                                }`}
                                onClick={() => note && openNote(note)}
                              >
                                {money(delta)} {note ? ' · 备注' : ''}
                              </Button>
                            );
                          },
                        },
                      ],
                    })),
                  ]}
                  pagination={false}
                />
              </Card>
            ),
          },
          {
            key: 'targets',
            label: '目标配置',
            children: (
              <Space orientation="vertical" style={{ width: '100%' }}>
                <Card
                  variant="borderless"
                  extra={
                    <Button
                      type="primary"
                      onClick={() => {
                        setEditingTarget(undefined);
                        targetForm.resetFields();
                        targetForm.setFieldsValue({ year });
                        setTargetOpen(true);
                      }}
                    >
                      新增 / 修改目标
                    </Button>
                  }
                >
                  <Table
                    rowKey={(r) =>
                      String(r.id || r.key || r.yearMonth || r.name || 'row')
                    }
                    dataSource={targets}
                    columns={[
                      { title: '报告组', dataIndex: 'reportGroup' },
                      {
                        title: '营收目标',
                        dataIndex: 'revenueTarget',
                        render: money,
                      },
                      {
                        title: '利润目标',
                        dataIndex: 'profitTarget',
                        render: money,
                      },
                      { title: '备注', dataIndex: 'remark' },
                      {
                        title: '操作',
                        render: (_: unknown, row: Record<string, unknown>) => (
                          <Button
                            type="link"
                            icon={<EditOutlined />}
                            onClick={() => {
                              setEditingTarget(row);
                              targetForm.setFieldsValue(row);
                              setTargetOpen(true);
                            }}
                          >
                            编辑
                          </Button>
                        ),
                      },
                    ]}
                    pagination={false}
                  />
                </Card>
                <Card
                  variant="borderless"
                  title={
                    <Space>
                      <SettingOutlined />
                      环比预警规则
                    </Space>
                  }
                >
                  <Table
                    rowKey={(r) =>
                      String(r.id || r.key || r.yearMonth || r.name || 'row')
                    }
                    dataSource={rules}
                    pagination={false}
                    columns={[
                      {
                        title: '适用范围',
                        render: (_: unknown, r: Record<string, unknown>) =>
                          r.reportGroup || '全局默认',
                      },
                      {
                        title: '周基准除数',
                        dataIndex: 'weeklyDivisor',
                        render: (v: unknown, r: Record<string, unknown>) => (
                          <InputNumber
                            min={1}
                            max={10}
                            value={v as number}
                            onChange={(n) => {
                              r.weeklyDivisor = n;
                              setRules([...rules]);
                            }}
                          />
                        ),
                      },
                      {
                        title: '偏小阈值',
                        dataIndex: 'yellowRatio',
                        render: (v: unknown, r: Record<string, unknown>) => (
                          <InputNumber
                            min={0}
                            max={1}
                            step={0.1}
                            value={v as number}
                            onChange={(n) => {
                              r.yellowRatio = n;
                              setRules([...rules]);
                            }}
                          />
                        ),
                      },
                      {
                        title: '启用',
                        dataIndex: 'enabled',
                        render: (v: unknown, r: Record<string, unknown>) => (
                          <Switch
                            checked={Boolean(v)}
                            onChange={(checked) => {
                              r.enabled = checked ? 1 : 0;
                              setRules([...rules]);
                            }}
                          />
                        ),
                      },
                      {
                        title: '操作',
                        render: (_: unknown, r: Record<string, unknown>) => (
                          <Button type="link" onClick={() => void saveRule(r)}>
                            保存
                          </Button>
                        ),
                      },
                    ]}
                  />
                </Card>
              </Space>
            ),
          },
          {
            key: 'worktime',
            label: '工时系统',
            children: (
              <Space orientation="vertical" style={{ width: '100%' }}>
                <Card
                  variant="borderless"
                  title="连接状态"
                  extra={
                    <Space>
                      <Button
                        onClick={() => {
                          worktimeForm.setFieldsValue({
                            enabled: Boolean(worktime.enabled),
                            baseUrl: worktime.baseUrl || '',
                            employeeNo: '',
                            password: '',
                          });
                          setWorktimeOpen(true);
                        }}
                      >
                        配置
                      </Button>
                      <Button
                        onClick={() =>
                          void superworkApi
                            .testWorktimeConnection()
                            .then((r) => {
                              const visible = Array.isArray(
                                r.visibleBusinessLines,
                              )
                                ? r.visibleBusinessLines.length
                                : undefined;
                              message.success(
                                String(
                                  r.message ||
                                    `连接成功${
                                      visible == null
                                        ? ''
                                        : `，可见业务线 ${visible} 条`
                                    }${
                                      r.dataCutoffDate
                                        ? `，数据截止 ${r.dataCutoffDate}`
                                        : ''
                                    }`,
                                ),
                              );
                            })
                            .catch((e) =>
                              message.error(
                                e instanceof Error ? e.message : '测试失败',
                              ),
                            )
                        }
                      >
                        测试连接
                      </Button>
                      <Button
                        loading={saving}
                        onClick={() => void syncWorktime('contracts')}
                      >
                        同步合同
                      </Button>
                      <Button
                        type="primary"
                        loading={saving}
                        onClick={() => void syncWorktime('monthly')}
                      >
                        同步月度
                      </Button>
                    </Space>
                  }
                >
                  <Typography.Text>
                    启用：{worktime.enabled ? '是' : '否'} · 配置完成：
                    {worktime.configured ? '是' : '否'} · 最近同步：
                    {String(worktime.lastSuccessfulSync || '—')}
                  </Typography.Text>
                </Card>
                <Card variant="borderless" title="同步日志">
                  <Table
                    size="small"
                    rowKey={(r) =>
                      String(r.id || r.key || r.yearMonth || r.name || 'row')
                    }
                    dataSource={syncLogs}
                    columns={Object.keys(syncLogs[0] || {})
                      .slice(0, 6)
                      .map((key) => ({
                        title: key,
                        dataIndex: key,
                        render: (v: unknown) => String(v ?? '—'),
                      }))}
                    pagination={{ pageSize: 8 }}
                  />
                </Card>
              </Space>
            ),
          },
        ]}
      />
      <Modal
        title="编辑异常备注"
        open={noteOpen}
        onCancel={() => setNoteOpen(false)}
        onOk={() => noteForm.submit()}
        okText="保存"
        cancelText="取消"
      >
        <Form
          form={noteForm}
          layout="vertical"
          onFinish={(values) => void saveNote(values)}
        >
          <Form.Item name="deviationReason" label="偏差原因">
            <Input.TextArea
              rows={3}
              placeholder="说明环比增量或目标偏差的原因"
            />
          </Form.Item>
          <Form.Item
            name="isAbnormal"
            label="是否异常"
            rules={[{ required: true }]}
          >
            <Select
              options={[
                { value: 1, label: '异常' },
                { value: 0, label: '正常' },
                { value: null, label: '待判断' },
              ]}
            />
          </Form.Item>
          <Form.Item name="countermeasure" label="对策">
            <Input.TextArea rows={3} placeholder="判定为异常时请填写具体对策" />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title={editingTarget ? '编辑 KPI 目标' : '新增 KPI 目标'}
        open={targetOpen}
        onCancel={() => setTargetOpen(false)}
        onOk={() => targetForm.submit()}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
      >
        <Form
          form={targetForm}
          layout="vertical"
          onFinish={(values) => void saveTarget(values)}
        >
          <Form.Item
            name="reportGroup"
            label="报告组"
            rules={[{ required: true }]}
          >
            <Input />
          </Form.Item>
          <Form.Item name="year" label="年份" rules={[{ required: true }]}>
            <InputNumber min={2020} max={2100} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name="revenueTarget"
            label="营收目标（元）"
            rules={[{ required: true }]}
          >
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name="profitTarget"
            label="利润目标（元）"
            rules={[{ required: true }]}
          >
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title="工时系统配置"
        open={worktimeOpen}
        onCancel={() => setWorktimeOpen(false)}
        onOk={() => worktimeForm.submit()}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
      >
        <Form
          form={worktimeForm}
          layout="vertical"
          onFinish={(values) => void saveWorktime(values)}
        >
          <Form.Item
            name="enabled"
            label="启用自动同步"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
          <Form.Item name="baseUrl" label="系统地址">
            <Input placeholder="https://worktime.lucidata.cn" />
          </Form.Item>
          <Form.Item name="employeeNo" label="登录工号">
            <Input
              placeholder={
                worktime.credentialConfigured
                  ? '已配置，留空保持不变'
                  : '如 00504'
              }
            />
          </Form.Item>
          <Form.Item name="password" label="登录密码">
            <Input.Password placeholder="留空保持不变" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
