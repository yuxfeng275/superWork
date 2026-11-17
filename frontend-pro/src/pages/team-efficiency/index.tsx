import { ReloadOutlined, TeamOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Col,
  Row,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { TableProps } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import {
  type TeamEfficiencyMember,
  type TeamEfficiencyProject,
  type TeamEfficiencyReport,
  superworkApi,
} from '@/services/superwork/api';
import '../workbench/style.less';

const percent = (value: number) => `${(value * 100).toFixed(1)}%`;

/**
 * 团队效能（M3）：主管每天看一页，回答「哪些项目没投递、谁卡住、谁超载」。
 *
 * 口径全部来自事实层与任务/制品表，不新造指标；事实层空的项目会被标出来，
 * 因为夜间产出没有原料时，谈 AI 效果没有意义。
 */
export default function TeamEfficiencyPage() {
  const [report, setReport] = useState<TeamEfficiencyReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setReport(await superworkApi.getTeamEfficiency(30));
    } catch (e) {
      setError(e instanceof Error ? e.message : '团队效能加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const projectColumns: TableProps<TeamEfficiencyProject>['columns'] = [
    {
      title: '项目',
      dataIndex: 'projectName',
      render: (value: string, row) => (
        <Space size={6}>
          <Typography.Text strong>{value}</Typography.Text>
          {row.facts === 0 ? <Tag color="red">30 天无投递</Tag> : null}
          {row.lowQuality > 0 ? <Tag color="orange">低质 {row.lowQuality}</Tag> : null}
        </Space>
      ),
    },
    { title: '事实', dataIndex: 'facts', width: 80 },
    { title: '投递人', dataIndex: 'contributors', width: 90 },
    { title: '待评审', dataIndex: 'pendingArtifacts', width: 90 },
    { title: '已采纳', dataIndex: 'approved', width: 90 },
    {
      title: '最近事实',
      dataIndex: 'lastFactAt',
      width: 170,
      render: (value?: string | null) =>
        value ? value.replace('T', ' ').slice(0, 16) : '—',
    },
  ];

  const memberColumns: TableProps<TeamEfficiencyMember>['columns'] = [
    { title: '成员', dataIndex: 'name' },
    { title: '贡献事实', dataIndex: 'facts', width: 100 },
    { title: '未完成', dataIndex: 'openTasks', width: 90 },
    {
      title: '逾期',
      dataIndex: 'overdueTasks',
      width: 90,
      render: (value: number) => (value > 0 ? <Tag color="red">{value}</Tag> : 0),
    },
    { title: '待评审制品', dataIndex: 'pendingArtifacts', width: 110 },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        title={
          <Space>
            <TeamOutlined />
            团队效能
          </Space>
        }
        extra={
          <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
            刷新
          </Button>
        }
      >
        <Typography.Paragraph type="secondary">
          近 30 天窗口；投递率固定按「近 7 天有事实的项目 / 启用项目」计算。
          事实层空的项目会标红：没有原料时，AI 夜间产出不可信。
        </Typography.Paragraph>
        {error ? <Alert type="error" showIcon message={error} /> : null}
        <Row gutter={16}>
          <Col xs={12} md={6}>
            <Statistic title="启用项目" value={report?.activeProjects ?? 0} loading={loading} />
          </Col>
          <Col xs={12} md={6}>
            <Statistic title="近 7 天有投递" value={report?.deliveredProjects ?? 0} loading={loading} />
          </Col>
          <Col xs={12} md={6}>
            <Statistic
              title="投递率"
              value={report ? percent(report.deliveryRate) : '—'}
              loading={loading}
            />
          </Col>
          <Col xs={12} md={6}>
            <Statistic title="待评审制品" value={report?.pendingArtifacts ?? 0} loading={loading} />
          </Col>
        </Row>
        <Space size={8} wrap style={{ marginTop: 12 }}>
          <Tag>30 天事实 {report?.totalFacts ?? 0}</Tag>
          <Tag color={(report?.lowQualityFacts ?? 0) > 0 ? 'orange' : undefined}>
            低质事实 {report?.lowQualityFacts ?? 0}
          </Tag>
          <Tag>{report?.days ?? 30} 天窗口</Tag>
        </Space>
      </Card>

      <Card title="项目投递健康" size="small">
        <Table
          rowKey="projectId"
          size="small"
          loading={loading}
          columns={projectColumns}
          dataSource={report?.projects || []}
          pagination={{ pageSize: 10, hideOnSinglePage: true }}
          locale={{ emptyText: '没有启用的项目' }}
        />
      </Card>

      <Card title="成员负载" size="small">
        <Table
          rowKey="userId"
          size="small"
          loading={loading}
          columns={memberColumns}
          dataSource={report?.members || []}
          pagination={{ pageSize: 10, hideOnSinglePage: true }}
          locale={{ emptyText: '近 30 天没有可统计的成员活动' }}
        />
      </Card>
    </Space>
  );
}
