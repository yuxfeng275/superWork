import {
  BookOutlined,
  CloudDownloadOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import {
  Button,
  Card,
  Empty,
  Form,
  Input,
  List,
  message,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { TableProps } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import {
  type KbDocument,
  type KbHealth,
  type KbHit,
  type KbImportResult,
  superworkApi,
} from '@/services/superwork/api';

/** 作用域中文名：检索时只有 COMPANY 与本人 OWNER 全量可见，其余需显式 scopeId。 */
const scopeLabels: Record<string, string> = {
  COMPANY: '公司',
  BUSINESS_LINE: '业务线',
  PROJECT: '项目',
  OWNER: '个人',
};

const sourceLabels: Record<string, string> = {
  MANUAL: '手工录入',
  YUQUE: '语雀',
  UPLOAD: '上传',
  CASE: '成交案例',
};

/**
 * 知识库管理页：看知识库里有什么、手工补一篇、从语雀整仓导入、启停文档，并现场搜一下看命中效果。
 *
 * <p>设计取舍：入库与启停是写操作（后端仅管理岗位可调）；检索对所有登录用户开放，用来验证“能不能被教练包用上”。
 */
export default function KnowledgePage() {
  const [documents, setDocuments] = useState<KbDocument[]>([]);
  const [health, setHealth] = useState<KbHealth | null>(null);
  const [loading, setLoading] = useState(false);
  const [ingestOpen, setIngestOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [importing, setImporting] = useState(false);
  const [importResult, setImportResult] = useState<KbImportResult | null>(null);
  const [query, setQuery] = useState('');
  const [hits, setHits] = useState<KbHit[]>([]);
  const [searching, setSearching] = useState(false);
  const [ingestForm] = Form.useForm();
  const [importForm] = Form.useForm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [list, report] = await Promise.all([
        superworkApi.getKnowledgeDocuments(100),
        superworkApi.getKnowledgeHealth(),
      ]);
      setDocuments(list || []);
      setHealth(report);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '知识库加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const search = async (keyword: string) => {
    const q = keyword.trim();
    if (!q) {
      setHits([]);
      return;
    }
    setSearching(true);
    try {
      const result = await superworkApi.searchKnowledge(q, 5);
      setHits(result || []);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '检索失败');
    } finally {
      setSearching(false);
    }
  };

  const toggle = async (row: KbDocument, value: boolean) => {
    try {
      await superworkApi.setKnowledgeDocumentEnabled(row.id, value);
      message.success(value ? '已启用，下次检索生效' : '已停用，不再进检索');
      void load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败');
    }
  };

  const submitIngest = async () => {
    const values = await ingestForm.validateFields();
    setSaving(true);
    try {
      await superworkApi.createKnowledgeDocument({
        title: values.title,
        content: values.content,
        sourceType: values.sourceType || 'MANUAL',
        scopeType: values.scopeType || 'COMPANY',
        businessLine: values.businessLine || null,
      });
      message.success('已入库并完成切块');
      setIngestOpen(false);
      ingestForm.resetFields();
      void load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '入库失败');
    } finally {
      setSaving(false);
    }
  };

  const submitImport = async () => {
    const values = await importForm.validateFields();
    setImporting(true);
    setImportResult(null);
    try {
      const result = await superworkApi.importKnowledgeFromYuque({
        repoId: values.repoId,
        scopeType: values.scopeType || 'COMPANY',
        businessLine: values.businessLine || null,
      });
      setImportResult(result);
      message.success(result?.message || '导入完成');
      void load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '导入失败');
    } finally {
      setImporting(false);
    }
  };

  const columns: TableProps<KbDocument>['columns'] = [
    {
      title: '标题',
      dataIndex: 'title',
      ellipsis: true,
      render: (value: string) => <Typography.Text strong>{value}</Typography.Text>,
    },
    {
      title: '来源',
      dataIndex: 'sourceType',
      width: 110,
      render: (value: string) => <Tag>{sourceLabels[value] || value}</Tag>,
    },
    {
      title: '可见范围',
      dataIndex: 'scopeType',
      width: 120,
      render: (value: string, row) => (
        <Tag color={value === 'COMPANY' ? 'blue' : 'default'}>
          {scopeLabels[value] || value}
          {row.scopeId ? `#${row.scopeId}` : ''}
        </Tag>
      ),
    },
    { title: '业务线', dataIndex: 'businessLine', width: 150, render: (v?: string) => v || '-' },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 90,
      render: (value: number, row) => (
        <Switch
          size="small"
          checked={value === 1}
          onChange={(checked) => void toggle(row, checked)}
        />
      ),
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 180,
      render: (value?: string) => (value ? value.replace('T', ' ').slice(0, 16) : '-'),
    },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        title={
          <Space>
            <BookOutlined />
            知识库
          </Space>
        }
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading}>
              刷新
            </Button>
            <Button icon={<CloudDownloadOutlined />} onClick={() => setImportOpen(true)}>
              语雀导入
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setIngestOpen(true)}>
              新增文档
            </Button>
          </Space>
        }
      >
        <Typography.Paragraph type="secondary">
          知识库供商机 AI 教练与 AI 助手检索：产品能力、标准方案、成功案例、报价与交付口径。
          切片与检索用 MySQL 全文索引（ngram），<strong>只有启用的文档才会被检索到</strong>。
        </Typography.Paragraph>
        {health ? (
          <Space size={8} wrap style={{ marginBottom: 12 }}>
            <Tag>共 {health.total} 篇</Tag>
            <Tag color="green">启用 {health.enabled}</Tag>
            <Tag>停用 {health.disabled}</Tag>
            <Tag color={health.duplicates > 0 ? 'orange' : undefined}>
              内容重复 {health.duplicates} 组
            </Tag>
            <Tag color={health.stale > 0 ? 'gold' : undefined}>
              超过 {health.staleDays} 天未更新 {health.stale}
            </Tag>
            {health.lastUpdatedAt ? (
              <Tag>最近更新 {health.lastUpdatedAt.replace('T', ' ').slice(0, 16)}</Tag>
            ) : null}
            {health.sources.map((item) => (
              <Tag key={item.sourceType}>
                {sourceLabels[item.sourceType] || item.sourceType} {item.enabled}/{item.total}
              </Tag>
            ))}
          </Space>
        ) : null}
        <Table
          rowKey="id"
          size="small"
          loading={loading}
          columns={columns}
          dataSource={documents}
          pagination={{ pageSize: 10, hideOnSinglePage: true }}
          locale={{ emptyText: '知识库还没有内容，先新增一篇或从语雀导入' }}
        />
      </Card>

      <Card title="检索预览" size="small">
        <Space.Compact style={{ width: '100%', marginBottom: 12 }}>
          <Input
            allowClear
            placeholder="输入关键词看看能命中什么，例如「会员通 交付周期」"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onPressEnter={() => void search(query)}
          />
          <Button
            type="primary"
            icon={<SearchOutlined />}
            loading={searching}
            onClick={() => void search(query)}
          >
            检索
          </Button>
        </Space.Compact>
        {hits.length === 0 ? (
          <Empty description="还没有检索，或没有命中" />
        ) : (
          <List
            size="small"
            dataSource={hits}
            renderItem={(hit) => (
              <List.Item>
                <List.Item.Meta
                  title={
                    <Space>
                      <Typography.Text strong>{hit.documentTitle}</Typography.Text>
                      {hit.heading ? <Tag>{hit.heading}</Tag> : null}
                      <Typography.Text type="secondary">
                        相关度 {hit.score.toFixed(4)}
                      </Typography.Text>
                    </Space>
                  }
                  description={hit.content.slice(0, 160)}
                />
              </List.Item>
            )}
          />
        )}
      </Card>

      <Modal
        title="新增知识文档"
        open={ingestOpen}
        onCancel={() => setIngestOpen(false)}
        onOk={() => void submitIngest()}
        confirmLoading={saving}
        okText="入库"
        cancelText="取消"
        width={760}
        destroyOnHidden
      >
        <Form form={ingestForm} layout="vertical" initialValues={{ sourceType: 'MANUAL', scopeType: 'COMPANY' }}>
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请填写标题' }]}>
            <Input placeholder="例如：会员通交付说明" />
          </Form.Item>
          <Form.Item name="content" label="正文" rules={[{ required: true, message: '请填写正文' }]}
            extra="支持 Markdown 原文；会按段落与标题切块，每块 400~600 字。">
            <Input.TextArea rows={10} placeholder="粘贴方案/案例/交付口径正文" />
          </Form.Item>
          <Space size={12} wrap>
            <Form.Item name="sourceType" label="来源">
              <Select
                style={{ width: 140 }}
                options={[
                  { value: 'MANUAL', label: '手工录入' },
                  { value: 'UPLOAD', label: '上传' },
                  { value: 'CASE', label: '成交案例' },
                ]}
              />
            </Form.Item>
            <Form.Item name="scopeType" label="可见范围">
              <Select
                style={{ width: 160 }}
                options={[
                  { value: 'COMPANY', label: '公司（全员可见）' },
                  { value: 'BUSINESS_LINE', label: '业务线' },
                  { value: 'OWNER', label: '个人' },
                ]}
              />
            </Form.Item>
            <Form.Item name="businessLine" label="业务线名称">
              <Input style={{ width: 200 }} placeholder="例如：全渠道云鹿定制" />
            </Form.Item>
          </Space>
        </Form>
      </Modal>

      <Modal
        title="从语雀导入"
        open={importOpen}
        onCancel={() => setImportOpen(false)}
        onOk={() => void submitImport()}
        confirmLoading={importing}
        okText="开始导入"
        cancelText="关闭"
        width={640}
        destroyOnHidden
      >
        <Typography.Paragraph type="secondary">
          填入语雀仓库 ID 或 slug（例如 <code>yufeng/member-hub</code>）。重复导入按内容幂等，不会产生重复切片。
        </Typography.Paragraph>
        <Form form={importForm} layout="vertical" initialValues={{ scopeType: 'COMPANY' }}>
          <Form.Item name="repoId" label="语雀仓库" rules={[{ required: true, message: '请填写仓库 ID 或 slug' }]}>
            <Input placeholder="namespace/repo" />
          </Form.Item>
          <Form.Item name="scopeType" label="导入后的可见范围">
            <Select
              options={[
                { value: 'COMPANY', label: '公司（全员可见）' },
                { value: 'BUSINESS_LINE', label: '业务线' },
              ]}
            />
          </Form.Item>
          <Form.Item name="businessLine" label="业务线名称">
            <Input placeholder="可选" />
          </Form.Item>
        </Form>
        {importResult ? (
          <Card size="small" title="导入结果" style={{ marginTop: 12 }}>
            <Typography.Paragraph>{importResult.message}</Typography.Paragraph>
            {importResult.failures?.length ? (
              <List
                size="small"
                header="失败清单"
                dataSource={importResult.failures}
                renderItem={(item) => <List.Item>{item}</List.Item>}
              />
            ) : null}
          </Card>
        ) : null}
      </Modal>
    </Space>
  );
}
