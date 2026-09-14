import {
  ArrowRightOutlined,
  ReloadOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { Link } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  Row,
  Skeleton,
  Space,
  Statistic,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { type Requirement, superworkApi } from '@/services/superwork/api';
import './style.less';

const inProgress = ['评估中', '设计中', '开发中', '测试中'];
const delivered = ['已上线', '已交付', '已验收'];

const tone = (status?: string) => {
  if (delivered.includes(status || '')) return 'success';
  if (status === '待上线' || status === '测试中') return 'warning';
  if (status === '已拒绝') return 'error';
  return 'processing';
};

export default function WorkbenchPage() {
  const [records, setRecords] = useState<Requirement[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const data = await superworkApi.getRequirements({ size: 100 });
      setRecords(data.records || []);
    } catch (e) {
      setError(e instanceof Error ? e.message : '工作台数据加载失败');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);
  const stats = useMemo(() => {
    const monthStart = new Date(
      new Date().getFullYear(),
      new Date().getMonth(),
      1,
    );
    return [
      {
        label: '进行中需求',
        value: records.filter((r) => inProgress.includes(r.status || ''))
          .length,
        note: '评估 / 设计 / 开发 / 测试',
        color: '#2563eb',
      },
      {
        label: '待上线',
        value: records.filter((r) => r.status === '待上线').length,
        note: '需要排入上线窗口',
        color: '#d97706',
      },
      {
        label: '本月交付',
        value: records.filter(
          (r) =>
            delivered.includes(r.status || '') &&
            r.updatedAt &&
            new Date(r.updatedAt) >= monthStart,
        ).length,
        note: '已上线 / 已交付 / 已验收',
        color: '#059669',
      },
      {
        label: '超期风险',
        value: records.filter(
          (r) =>
            r.priority === '高' &&
            ['待评估', '评估中'].includes(r.status || ''),
        ).length,
        note: '高优先级待决策事项',
        color: '#dc2626',
      },
    ];
  }, [records]);
  return (
    <div className="sw-page sw-workbench">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            WORKSPACE / OVERVIEW
          </Typography.Text>
          <Typography.Title level={2}>工作台</Typography.Title>
          <Typography.Paragraph type="secondary">
            用一眼可读的状态，决定今天先推进什么。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新数据
          </Button>
          <Link to="/requirements">
            <Button type="primary" icon={<ArrowRightOutlined />}>
              查看需求池
            </Button>
          </Link>
        </Space>
      </div>
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取生产数据"
          description={error}
          action={
            <Button size="small" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      )}
      <Row gutter={[16, 16]} className="sw-stat-row">
        {stats.map((stat) => (
          <Col xs={24} sm={12} xl={6} key={stat.label}>
            <Card className="sw-stat-card" variant="borderless">
              <Statistic
                title={stat.label}
                value={loading ? '-' : stat.value}
                styles={{ content: { color: stat.color } }}
              />
              <Typography.Text type="secondary">{stat.note}</Typography.Text>
            </Card>
          </Col>
        ))}
      </Row>
      <Row gutter={[16, 16]}>
        <Col xs={24} xl={15}>
          <Card
            variant="borderless"
            title={
              <div>
                <Typography.Text strong>最近需求</Typography.Text>
                <Typography.Text type="secondary" className="sw-card-subtitle">
                  按生产接口返回顺序展示
                </Typography.Text>
              </div>
            }
            extra={
              <Link to="/requirements">
                全部需求 <ArrowRightOutlined />
              </Link>
            }
          >
            {loading ? (
              <Skeleton active paragraph={{ rows: 5 }} />
            ) : records.length ? (
              <ul className="sw-recent-list">
                {records.slice(0, 6).map((item) => (
                  <li className="sw-recent-item" key={String(item.id)}>
                    <div className="sw-recent-meta">
                      <Link
                        className="sw-recent-title"
                        to={`/requirements?id=${item.id}`}
                      >
                        {item.title}
                      </Link>
                      <Typography.Text type="secondary">
                        {item.reqNo || `REQ-${item.id}`} ·{' '}
                        {item.projectName || item.project || '未关联项目'} ·{' '}
                        {item.owner || '未分配负责人'}
                      </Typography.Text>
                    </div>
                    <Tag color={tone(item.status)}>
                      {item.status || '未设置'}
                    </Tag>
                  </li>
                ))}
              </ul>
            ) : (
              <Empty description="暂无需求数据" />
            )}
          </Card>
        </Col>
        <Col xs={24} xl={9}>
          <Card
            variant="borderless"
            title="今日关注"
            className="sw-attention-card"
          >
            <div className="sw-attention-item">
              <span className="sw-attention-icon danger">
                <WarningOutlined />
              </span>
              <div>
                <Typography.Text strong>高优先级评估事项</Typography.Text>
                <Typography.Paragraph type="secondary">
                  {stats[3].value
                    ? `当前有 ${stats[3].value} 项需要尽快给出决策。`
                    : '暂无高优先级评估风险。'}
                </Typography.Paragraph>
              </div>
            </div>
            <div className="sw-attention-item">
              <span className="sw-attention-icon blue">
                <ArrowRightOutlined />
              </span>
              <div>
                <Typography.Text strong>查看完整工作流</Typography.Text>
                <Typography.Paragraph type="secondary">
                  需求、任务、缺陷使用同一套状态和数据来源。
                </Typography.Paragraph>
                <Link to="/tasks">进入任务管理</Link>
              </div>
            </div>
          </Card>
        </Col>
      </Row>
    </div>
  );
}
