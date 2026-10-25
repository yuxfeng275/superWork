import {
  DeleteOutlined,
  EyeOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  Form,
  Input,
  InputNumber,
  message,
  Modal,
  Row,
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
  type QuotationTemplate,
  type QuotationTemplateClause,
  superworkApi,
} from '@/services/superwork/api';
import '../workbench/style.less';

const typeLabel: Record<string, string> = {
  SAAS: 'SaaS 全渠道',
  PRIVATE_DEPLOYMENT: '私有化部署',
  MEMBERSHIP: '会员通',
  COMMON: '通用',
};

const blankClause = (): QuotationTemplateClause => ({
  section: '报价说明',
  title: '',
  content: '',
  isEnabled: 1,
});

/** 报价模板：把报价说明/条款从手填变成可复用的条款库，支持 {{customerName}} 等变量。 */
export default function QuotationTemplatesPage() {
  const [rows, setRows] = useState<QuotationTemplate[]>([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState<QuotationTemplate>();
  const [form] = Form.useForm<Partial<QuotationTemplate>>();
  const [clauses, setClauses] = useState<QuotationTemplateClause[]>([]);
  const [clausesLoading, setClausesLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [preview, setPreview] = useState<Record<string, string[]>>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setRows(await superworkApi.getQuotationTemplates());
    } catch (e) {
      setRows([]);
      message.error(e instanceof Error ? e.message : '模板加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const openTemplate = async (row: QuotationTemplate) => {
    setEditing(row);
    setOpen(true);
    form.setFieldsValue(row);
    setClausesLoading(true);
    try {
      setClauses(await superworkApi.getQuotationTemplateClauses(row.id));
    } catch (e) {
      setClauses([]);
      message.error(e instanceof Error ? e.message : '条款加载失败');
    } finally {
      setClausesLoading(false);
    }
  };

  const createTemplate = async () => {
    const values = await form.validateFields();
    try {
      const created = await superworkApi.saveQuotationTemplate(values);
      message.success('模板已创建');
      setOpen(false);
      await load();
      await openTemplate(created);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '创建失败');
    }
  };

  const saveTemplate = async () => {
    if (!editing) return;
    const values = await form.validateFields();
    try {
      await superworkApi.saveQuotationTemplate({ ...values, id: editing.id });
      await superworkApi.replaceQuotationTemplateClauses(editing.id, clauses);
      message.success('模板与条款已保存');
      setOpen(false);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败');
    }
  };

  const publish = async (row: QuotationTemplate) => {
    await superworkApi.publishQuotationTemplate(row.id);
    message.success('模板已发布');
    await load();
  };

  const remove = (row: QuotationTemplate) => {
    Modal.confirm({
      title: '删除模板',
      content: `确定删除「${row.name}」及其条款吗？`,
      okButtonProps: { danger: true },
      onOk: async () => {
        await superworkApi.deleteQuotationTemplate(row.id);
        message.success('已删除');
        await load();
      },
    });
  };

  const showPreview = async (row: QuotationTemplate) => {
    try {
      setPreview(await superworkApi.previewQuotationTemplate(row.id));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '预览失败');
    }
  };

  const columns: TableProps<QuotationTemplate>['columns'] = [
    {
      title: '模板名称',
      dataIndex: 'name',
      render: (value, row) => (
        <div>
          <Typography.Link strong onClick={() => void openTemplate(row)}>
            {value}
          </Typography.Link>
          <Typography.Text type="secondary" style={{ display: 'block' }}>
            {row.title || '未设置抬头'}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: '适用类型',
      dataIndex: 'type',
      width: 140,
      render: (value) => <Tag color="blue">{typeLabel[value] || value}</Tag>,
    },
    {
      title: '有效期',
      dataIndex: 'defaultValidityDays',
      width: 100,
      render: (value) => `${value || 30} 天`,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (value) => (
        <Tag color={value === 'PUBLISHED' ? 'success' : 'default'}>{value}</Tag>
      ),
    },
    {
      title: '操作',
      width: 260,
      render: (_, row) => (
        <Space size={0}>
          <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => void showPreview(row)}>
            预览
          </Button>
          {row.status !== 'PUBLISHED' && (
            <Button type="link" size="small" onClick={() => void publish(row)}>
              发布
            </Button>
          )}
          <Button type="link" size="small" danger icon={<DeleteOutlined />} onClick={() => remove(row)}>
            删除
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div className="sw-page">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">SALES / QUOTATION TEMPLATES</Typography.Text>
          <Typography.Title level={2}>报价模板管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            报价说明、交付与付款条款集中维护；支持
            {' {{customerName}} '}、{'{{quoteDate}} '}、{'{{validityDays}}'} 变量在导出时替换。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              setEditing(undefined);
              setClauses([blankClause()]);
              form.resetFields();
              setOpen(true);
            }}
          >
            新建模板
          </Button>
        </Space>
      </div>
      <Card variant="borderless">
        <Table
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={rows}
          pagination={false}
          locale={{ emptyText: <Empty description="暂无报价模板" /> }}
        />
      </Card>

      <Modal
        title={editing ? `编辑模板：${editing.name}` : '新建报价模板'}
        open={open}
        width={960}
        onCancel={() => setOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setOpen(false)}>取消</Button>
            <Button
              type="primary"
              icon={<SaveOutlined />}
              onClick={() => void (editing ? saveTemplate() : createTemplate())}
            >
              保存
            </Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical">
          <Row gutter={12}>
            <Col span={8}>
              <Form.Item name="name" label="模板名称" rules={[{ required: true }]}>
                <Input />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="type" label="适用类型" rules={[{ required: true }]}>
                <Select
                  options={Object.entries(typeLabel).map(([value, label]) => ({ value, label }))}
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="defaultValidityDays" label="默认有效期（天）">
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="title" label="报价单抬头">
                <Input />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="issuer" label="报价方落款">
                <Input />
              </Form.Item>
            </Col>
          </Row>
        </Form>

        <Typography.Title level={5}>条款明细</Typography.Title>
        {clausesLoading ? <Empty description="条款加载中" /> : null}
        <Space direction="vertical" style={{ width: '100%' }}>
          {clauses.map((clause, index) => (
            <Card key={index} size="small">
              <Row gutter={8} align="top">
                <Col span={5}>
                  <Input
                    placeholder="分组"
                    value={clause.section}
                    onChange={(e) => {
                      const next = [...clauses];
                      next[index] = { ...clause, section: e.target.value };
                      setClauses(next);
                    }}
                  />
                </Col>
                <Col span={6}>
                  <Input
                    placeholder="条款标题"
                    value={clause.title}
                    onChange={(e) => {
                      const next = [...clauses];
                      next[index] = { ...clause, title: e.target.value };
                      setClauses(next);
                    }}
                  />
                </Col>
                <Col span={10}>
                  <Input.TextArea
                    rows={2}
                    placeholder="条款正文，可用 {{customerName}} / {{quoteDate}} / {{validityDays}}"
                    value={clause.content}
                    onChange={(e) => {
                      const next = [...clauses];
                      next[index] = { ...clause, content: e.target.value };
                      setClauses(next);
                    }}
                  />
                </Col>
                <Col span={3}>
                  <Space>
                    <Switch
                      checked={clause.isEnabled !== 0}
                      onChange={(checked) => {
                        const next = [...clauses];
                        next[index] = { ...clause, isEnabled: checked ? 1 : 0 };
                        setClauses(next);
                      }}
                    />
                    <Button
                      type="text"
                      danger
                      icon={<DeleteOutlined />}
                      onClick={() => setClauses(clauses.filter((_, i) => i !== index))}
                    />
                  </Space>
                </Col>
              </Row>
            </Card>
          ))}
          <Button icon={<PlusOutlined />} onClick={() => setClauses([...clauses, blankClause()])}>
            新增条款
          </Button>
        </Space>
      </Modal>

      <Modal
        title="条款预览"
        open={!!preview}
        onCancel={() => setPreview(undefined)}
        footer={null}
        width={720}
      >
        {preview && Object.keys(preview).length ? (
          Object.entries(preview).map(([section, lines]) => (
            <div key={section} style={{ marginBottom: 12 }}>
              <Typography.Title level={5}>{section}</Typography.Title>
              {lines.map((line) => (
                <Typography.Paragraph key={line}>{line}</Typography.Paragraph>
              ))}
            </div>
          ))
        ) : (
          <Alert type="info" message="该模板暂无已启用条款" />
        )}
      </Modal>
    </div>
  );
}
