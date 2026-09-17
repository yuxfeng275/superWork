import { ReloadOutlined, SettingOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  message,
  Row,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { superworkApi } from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';
import DashboardPage from './dashboard';

const labelMap: Record<string, string> = {
  total: '总量',
  totalCount: '总数',
  completed: '已完成',
  completedCount: '已完成',
  inProgress: '进行中',
  inProgressCount: '进行中',
  overdue: '逾期',
  planned: '计划负荷',
  actual: '实际投入',
  revenue: '营收',
  profit: '利润',
};
const human = (key: string) =>
  labelMap[key] ||
  key.replace(/([A-Z])/g, ' $1').replace(/^./, (c) => c.toUpperCase());
const isScalar = (v: unknown): v is string | number =>
  typeof v === 'string' || typeof v === 'number';
function StatisticsPageLegacy() {
  const [data, setData] = useState<Record<string, unknown>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setData(await superworkApi.getStatistics());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'BU 驾驶舱加载失败');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);
  const scalarEntries = useMemo(
    () =>
      Object.entries(data)
        .filter(([, v]) => isScalar(v))
        .slice(0, 8),
    [data],
  );
  const listEntries = useMemo(
    () => Object.entries(data).filter(([, v]) => Array.isArray(v)),
    [data],
  );
  return (
    <div className="sw-page sw-statistics">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            DATA / BU DASHBOARD
          </Typography.Text>
          <Typography.Title level={2}>BU 驾驶舱</Typography.Title>
          <Typography.Paragraph type="secondary">
            面向管理者的交付、工时与计划负荷总览；计划负荷与实际投入分开展示，未知值保持中性。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          <Button
            icon={<SettingOutlined />}
            onClick={() =>
              message.info('驾驶舱配置入口保留，具体映射由系统配置维护')
            }
          >
            配置
          </Button>
        </Space>
      </div>
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取驾驶舱数据"
          description={error}
          action={<Button onClick={() => void load()}>重试</Button>}
        />
      )}
      <Tabs
        items={[
          {
            key: 'overview',
            label: '执行总览',
            children: (
              <>
                <Row gutter={[14, 14]}>
                  {scalarEntries.map(([k, v]) => (
                    <Col xs={12} md={6} key={k}>
                      <Card variant="borderless">
                        <Statistic
                          title={human(k)}
                          value={v as string | number}
                        />
                      </Card>
                    </Col>
                  ))}
                </Row>
                <Card
                  variant="borderless"
                  className="sw-table-card"
                  loading={loading}
                  title="数据分解"
                >
                  {listEntries.length ? (
                    <Space orientation="vertical" style={{ width: '100%' }}>
                      {listEntries.map(([key, value]) => (
                        <Card size="small" key={key} title={human(key)}>
                          <Table
                            size="small"
                            rowKey={(row) =>
                              String(
                                (row as Record<string, unknown>).id ||
                                  (row as Record<string, unknown>).name ||
                                  key,
                              )
                            }
                            pagination={{ pageSize: 8 }}
                            dataSource={(
                              value as Record<string, unknown>[]
                            ).map((row, i) => ({ ...row, _i: i }))}
                            columns={Object.keys(
                              (value as Record<string, unknown>[])[0] || {},
                            )
                              .filter((k) => k !== '_i')
                              .slice(0, 8)
                              .map((k) => ({
                                title: human(k),
                                dataIndex: k,
                                key: k,
                                render: (x: unknown) =>
                                  isScalar(x) ? x : JSON.stringify(x),
                              }))}
                          />
                        </Card>
                      ))}
                    </Space>
                  ) : (
                    <Empty description="暂无明细数据" />
                  )}
                </Card>
              </>
            ),
          },
          {
            key: 'evidence',
            label: '口径说明',
            children: (
              <Card variant="borderless">
                <Typography.Paragraph>
                  本页只消费后端驾驶舱数据，不改变统计口径。实际投入、计划负荷、营收和利润等指标分别保留原始字段；接口缺失或尚未同步时展示“—”，不以
                  0 代替。
                </Typography.Paragraph>
                <Tag color="blue">只读分析</Tag>
              </Card>
            ),
          },
        ]}
      />
    </div>
  );
}

void StatisticsPageLegacy;
export default DashboardPage;
