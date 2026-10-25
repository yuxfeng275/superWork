import {
  CopyOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { useModel } from '@umijs/max';
import type { TableProps } from 'antd';
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  message,
  Popconfirm,
  Row,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import { useCallback, useEffect, useState } from 'react';
import {
  type QuotationPolicy,
  type QuotationPolicyItem,
  type QuotationTemplate,
  superworkApi,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

const types = [
  { label: 'SaaS全渠道', value: 'SAAS' },
  { label: '私有化全渠道', value: 'PRIVATE_DEPLOYMENT' },
  { label: '会员通', value: 'MEMBERSHIP' },
];
const taxModes = [
  { label: '含税', value: 'TAX_INCLUDED' },
  { label: '未税', value: 'TAX_EXCLUDED' },
];
const sections = ['产品模块', '运维服务', '定制开发', '会员通对接'];
const statusLabel: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  ARCHIVED: '已归档',
};
const statusColor: Record<string, string> = {
  DRAFT: 'default',
  PUBLISHED: 'success',
  ARCHIVED: 'warning',
};
const typeLabel = (value: string) =>
  types.find((item) => item.value === value)?.label || value;
const taxLabel = (value: string) =>
  taxModes.find((item) => item.value === value)?.label || value;
type PolicyForm = {
  name: string;
  type: string;
  templateId?: number;
  minDiscountRate?: number;
  approvalRequired?: number;
  taxMode: string;
  effectiveDate?: dayjs.Dayjs;
  expiryDate?: dayjs.Dayjs;
};
type ItemForm = Omit<QuotationPolicyItem, 'id' | 'policyId'>;

const blankItem = (): ItemForm => ({
  section: '',
  category: '',
  itemKey: '',
  itemName: '',
  description: '',
  priceDescription: '',
  isRequired: 0,
  unitPrice: undefined,
  costPrice: undefined,
  taxRate: undefined,
  chargeMethod: '',
  chargeBaseSection: undefined,
  chargeRate: undefined,
    firstYearFree: 0,
  chargeUnit: '',
  remark: '',
  sortOrder: 0,
});

export default function QuotationPoliciesPage() {
  const { initialState } = useModel('@@initialState');
  const canManage = [
    'admin',
    '系统管理员',
    'DIRECTOR',
    'DEPUTY_DIRECTOR',
    'BUSINESS_OWNER',
    'EFFECTIVENESS_OWNER',
    'BU_ADMIN',
  ].includes(initialState?.currentUser?.role || '');
  const [filterForm] = Form.useForm<{
    type?: string;
    taxMode?: string;
    status?: string;
  }>();
  const [policyForm] = Form.useForm<PolicyForm>();
  const [itemForm] = Form.useForm<ItemForm>();
  const [rows, setRows] = useState<QuotationPolicy[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState<QuotationPolicy>();
  const [policyOpen, setPolicyOpen] = useState(false);
  const [policySaving, setPolicySaving] = useState(false);
  const [itemsOpen, setItemsOpen] = useState(false);
  const [itemFormOpen, setItemFormOpen] = useState(false);
  const [itemSaving, setItemSaving] = useState(false);
  const [currentPolicy, setCurrentPolicy] = useState<QuotationPolicy>();
  const [items, setItems] = useState<QuotationPolicyItem[]>([]);
  const [itemsLoading, setItemsLoading] = useState(false);
  const [editingItem, setEditingItem] = useState<QuotationPolicyItem>();
  const [templates, setTemplates] = useState<QuotationTemplate[]>([]);

  useEffect(() => {
    void (async () => {
      try {
        setTemplates(await superworkApi.getQuotationTemplates({ status: 'PUBLISHED' }));
      } catch {
        setTemplates([]);
      }
    })();
  }, []);

  const load = useCallback(
    async (values = filterForm.getFieldsValue()) => {
      setLoading(true);
      setError('');
      try {
        setRows(await superworkApi.getQuotationPolicies(values));
      } catch (e) {
        setRows([]);
        setError(e instanceof Error ? e.message : '报价策略加载失败');
      } finally {
        setLoading(false);
      }
    },
    [filterForm],
  );
  useEffect(() => {
    void load();
  }, [load]);
  const openCreate = () => {
    setEditing(undefined);
    policyForm.setFieldsValue({
      name: '',
      type: '',
      templateId: undefined,
      minDiscountRate: 0.85,
      approvalRequired: 1,
      taxMode: '',
      effectiveDate: undefined,
      expiryDate: undefined,
    });
    setPolicyOpen(true);
  };
  const openEdit = (row: QuotationPolicy) => {
    setEditing(row);
    policyForm.setFieldsValue({
      name: row.name,
      type: row.type,
      taxMode: row.taxMode,
      effectiveDate: row.effectiveDate ? dayjs(row.effectiveDate) : undefined,
      expiryDate: row.expiryDate ? dayjs(row.expiryDate) : undefined,
    });
    setPolicyOpen(true);
  };
  const savePolicy = async () => {
    const values = await policyForm.validateFields();
    setPolicySaving(true);
    try {
      const payload = {
        name: values.name.trim(),
        type: values.type,
        taxMode: values.taxMode,
        templateId: values.templateId,
        minDiscountRate: values.minDiscountRate,
        approvalRequired: values.approvalRequired ?? 1,
        effectiveDate: values.effectiveDate?.format('YYYY-MM-DD'),
        expiryDate: values.expiryDate?.format('YYYY-MM-DD'),
      };
      if (editing)
        await superworkApi.updateQuotationPolicy(editing.id, payload);
      else await superworkApi.createQuotationPolicy(payload);
      message.success(editing ? '策略已更新' : '策略已创建');
      setPolicyOpen(false);
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '策略保存失败');
    } finally {
      setPolicySaving(false);
    }
  };
  const doAction = async (
    row: QuotationPolicy,
    action: 'publish' | 'archive' | 'delete',
  ) => {
    try {
      if (action === 'publish')
        await superworkApi.publishQuotationPolicy(row.id);
      if (action === 'archive')
        await superworkApi.archiveQuotationPolicy(row.id);
      if (action === 'delete') await superworkApi.deleteQuotationPolicy(row.id);
      message.success(
        action === 'publish'
          ? '策略已发布'
          : action === 'archive'
            ? '策略已归档'
            : '策略已删除',
      );
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '策略操作失败');
    }
  };
  const openItems = async (row: QuotationPolicy) => {
    setCurrentPolicy(row);
    setItemsOpen(true);
    setItemsLoading(true);
    try {
      setItems(await superworkApi.getQuotationPolicyItems(row.id));
    } catch (e) {
      setItems([]);
      message.error(e instanceof Error ? e.message : '明细项加载失败');
    } finally {
      setItemsLoading(false);
    }
  };
  const openAddItem = () => {
    setEditingItem(undefined);
    itemForm.setFieldsValue(blankItem());
    setItemFormOpen(true);
  };
  const openEditItem = (item: QuotationPolicyItem) => {
    setEditingItem(item);
    itemForm.setFieldsValue({ ...item });
    setItemFormOpen(true);
  };
  const saveItem = async () => {
    if (!currentPolicy) return;
    const values = await itemForm.validateFields();
    setItemSaving(true);
    try {
      if (editingItem)
        await superworkApi.updateQuotationPolicyItem(editingItem.id, values);
      else
        await superworkApi.addQuotationPolicyItems(currentPolicy.id, [values]);
      message.success(editingItem ? '明细项已更新' : '明细项已添加');
      setItemFormOpen(false);
      setItems(await superworkApi.getQuotationPolicyItems(currentPolicy.id));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '明细项保存失败');
    } finally {
      setItemSaving(false);
    }
  };
  const policyColumns: TableProps<QuotationPolicy>['columns'] = [
    {
      title: '策略名称',
      dataIndex: 'name',
      render: (value, row) => (
        <div>
          <Typography.Text strong>{value}</Typography.Text>
          <Typography.Text type="secondary" className="sw-policy-meta">
            V{row.version}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: '策略类型',
      dataIndex: 'type',
      render: (value) => <Tag>{typeLabel(value)}</Tag>,
    },
    {
      title: '计税模式',
      dataIndex: 'taxMode',
      width: 100,
      render: (value) => taxLabel(value),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (value) => (
        <Tag color={statusColor[value]}>{statusLabel[value] || value}</Tag>
      ),
    },
    {
      title: '生效日期',
      dataIndex: 'effectiveDate',
      width: 130,
      render: (value) => value?.slice(0, 10) || '—',
    },
    {
      title: '操作',
      key: 'action',
      width: 320,
      render: (_: unknown, row: QuotationPolicy) => (
        <Space size={0}>
          {canManage && (
            <Button
              type="link"
              icon={<EditOutlined />}
              disabled={row.status !== 'DRAFT'}
              onClick={() => openEdit(row)}
            >
              编辑
            </Button>
          )}
          {canManage && row.status === 'PUBLISHED' && (
            <Popconfirm
              title="基于该版本新建一个可编辑的下一版？"
              description="发布后价格不能直接修改，必须派生新版本。"
              okText="新建版本"
              cancelText="取消"
              onConfirm={async () => {
                try {
                  const forked = await superworkApi.forkQuotationPolicy(row.id);
                  message.success(`已派生草稿 V${forked.version}`);
                  await load();
                } catch (e) {
                  message.error(e instanceof Error ? e.message : '派生版本失败');
                }
              }}
            >
              <Button type="link" icon={<CopyOutlined />}>
                新建版本
              </Button>
            </Popconfirm>
          )}
          <Button
            type="link"
            icon={<SettingOutlined />}
            onClick={() => void openItems(row)}
          >
            明细
          </Button>
          {canManage && row.status === 'DRAFT' && (
            <Popconfirm
              title={`发布「${row.name}」吗？`}
              description="发布后旧同类型策略将被归档。"
              okText="发布"
              cancelText="取消"
              onConfirm={() => void doAction(row, 'publish')}
            >
              <Button type="link">发布</Button>
            </Popconfirm>
          )}
          {canManage && row.status === 'PUBLISHED' && (
            <Popconfirm
              title={`归档「${row.name}」吗？`}
              okText="归档"
              cancelText="取消"
              onConfirm={() => void doAction(row, 'archive')}
            >
              <Button type="link">归档</Button>
            </Popconfirm>
          )}
          {canManage && row.status === 'DRAFT' && (
            <Popconfirm
              title={`删除「${row.name}」吗？`}
              okText="删除"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              onConfirm={() => void doAction(row, 'delete')}
            >
              <Button type="link" danger icon={<DeleteOutlined />}>
                删除
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];
  const itemColumns: TableProps<QuotationPolicyItem>['columns'] = [
    { title: '分组', dataIndex: 'section', width: 120 },
    {
      title: '明细项',
      dataIndex: 'itemName',
      render: (value, row) => (
        <div>
          <Typography.Text strong>{value}</Typography.Text>
          <Typography.Text type="secondary" className="sw-policy-meta">
            {row.itemKey}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: '必选',
      dataIndex: 'isRequired',
      width: 70,
      render: (value) => (value ? <Tag color="blue">是</Tag> : '否'),
    },
    {
      title: '未税单价 / 计费率',
      dataIndex: 'unitPrice',
      width: 140,
      render: (value, row) =>
        row.chargeMethod === 'PERCENT_OF_BASE'
            ? `${((row.chargeRate ?? 0) * 100).toFixed(1)}% × ${row.chargeBaseSection || '分组小计'}`
          : value == null
            ? '—'
            : `¥ ${Number(value).toFixed(2)}`,
    },
    {
      title: '首年免费',
      dataIndex: 'firstYearFree',
      width: 90,
      render: (value) => (value ? <Tag color="green">是</Tag> : '否'),
    },
    {
      title: '税率',
      dataIndex: 'taxRate',
      width: 80,
      render: (value) => (value == null ? '—' : `${value}%`),
    },
    {
      title: '收费方式',
      dataIndex: 'chargeMethod',
      width: 110,
      render: (value) => value || '—',
    },
    {
      title: '操作',
      key: 'action',
      width: 130,
      render: (_: unknown, row: QuotationPolicyItem) => (
        <Space>
          <Button
            type="link"
            icon={<EditOutlined />}
            onClick={() => openEditItem(row)}
          >
            编辑
          </Button>
          <Popconfirm
            title={`删除「${row.itemName}」吗？`}
            okText="删除"
            cancelText="取消"
            okButtonProps={{ danger: true }}
            onConfirm={async () => {
              try {
                await superworkApi.deleteQuotationPolicyItem(row.id);
                message.success('明细项已删除');
                if (currentPolicy)
                  setItems(
                    await superworkApi.getQuotationPolicyItems(
                      currentPolicy.id,
                    ),
                  );
              } catch (e) {
                message.error(
                  e instanceof Error ? e.message : '删除明细项失败',
                );
              }
            }}
          >
            <Button type="link" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div className="sw-page sw-quotation-policies">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            SALES / QUOTATION POLICIES
          </Typography.Text>
          <Typography.Title level={2}>报价策略管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            统一维护策略版本、计税模式和报价明细，供报价单生成使用。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          {canManage && (
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              创建策略
            </Button>
          )}
        </Space>
      </div>
      <Card variant="borderless" className="sw-filter-card">
        <Form form={filterForm} layout="inline" onFinish={() => void load()}>
          <Form.Item name="type" label="策略类型">
            <Select
              allowClear
              placeholder="全部类型"
              style={{ width: 160 }}
              options={types}
            />
          </Form.Item>
          <Form.Item name="taxMode" label="计税模式">
            <Select
              allowClear
              placeholder="全部模式"
              style={{ width: 130 }}
              options={taxModes}
            />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select
              allowClear
              placeholder="全部状态"
              style={{ width: 120 }}
              options={[
                { label: '草稿', value: 'DRAFT' },
                { label: '已发布', value: 'PUBLISHED' },
                { label: '已归档', value: 'ARCHIVED' },
              ]}
            />
          </Form.Item>
          <Space>
            <Button type="primary" htmlType="submit">
              查询
            </Button>
            <Button
              onClick={() => {
                filterForm.resetFields();
                void load({});
              }}
            >
              重置
            </Button>
          </Space>
        </Form>
      </Card>
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取报价策略"
          description={error}
          action={
            <Button size="small" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      )}
      <Card variant="borderless" className="sw-table-card">
        <Table
          rowKey="id"
          loading={loading}
          columns={policyColumns}
          dataSource={rows}
          locale={{ emptyText: <Empty description="暂无报价策略" /> }}
          scroll={{ x: 1000 }}
          pagination={false}
        />
      </Card>
      <Modal
        title={editing ? '编辑策略' : '创建策略'}
        open={policyOpen}
        forceRender
        onCancel={() => setPolicyOpen(false)}
        onOk={() => void savePolicy()}
        okText="保存"
        cancelText="取消"
        confirmLoading={policySaving}
        destroyOnHidden
      >
        <Form form={policyForm} layout="vertical">
          <Form.Item
            name="name"
            label="策略名称"
            rules={[{ required: true, message: '请输入策略名称' }]}
          >
            <Input maxLength={120} />
          </Form.Item>
          <Form.Item
            name="type"
            label="策略类型"
            rules={[{ required: true, message: '请选择策略类型' }]}
          >
            <Select options={types} />
          </Form.Item>
          <Form.Item
            name="taxMode"
            label="计税模式"
            rules={[{ required: true, message: '请选择计税模式' }]}
          >
            <Select options={taxModes} />
          </Form.Item>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="templateId" label="绑定报价模板">
                <Select
                  allowClear
                  placeholder="选择条款模板"
                  options={templates.map((item) => ({
                    label: `${item.name}（${typeLabel(item.type)}）`,
                    value: item.id,
                  }))}
                />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item
                name="minDiscountRate"
                label="底价折扣"
                tooltip="低于该折扣需走折扣审批，0.85 表示 85 折"
              >
                <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item
                name="approvalRequired"
                label="折扣审批"
                valuePropName="checked"
                getValueFromEvent={(checked: boolean) => (checked ? 1 : 0)}
              >
                <Switch checkedChildren="开" unCheckedChildren="关" />
              </Form.Item>
            </Col>
          </Row>
          <Space>
            <Form.Item name="effectiveDate" label="生效日期">
              <DatePicker />
            </Form.Item>
            <Form.Item name="expiryDate" label="失效日期">
              <DatePicker />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
      <Modal
        title={`策略明细 — ${currentPolicy?.name || ''}`}
        open={itemsOpen}
        onCancel={() => setItemsOpen(false)}
        width={980}
        footer={
          canManage ? (
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={openAddItem}
            >
              添加明细项
            </Button>
          ) : null
        }
      >
        <Table
          rowKey="id"
          size="small"
          loading={itemsLoading}
          columns={itemColumns}
          dataSource={items}
          locale={{ emptyText: <Empty description="暂无明细项" /> }}
          scroll={{ x: 850 }}
          pagination={false}
        />
      </Modal>
      <Modal
        title={editingItem ? '编辑明细项' : '添加明细项'}
        open={itemFormOpen}
        forceRender
        onCancel={() => setItemFormOpen(false)}
        onOk={() => void saveItem()}
        okText="保存"
        cancelText="取消"
        confirmLoading={itemSaving}
        width={680}
        destroyOnHidden
      >
        <Form form={itemForm} layout="vertical">
          <Space align="start" style={{ width: '100%' }}>
            <Form.Item
              name="section"
              label="报价分组"
              rules={[{ required: true, message: '请选择报价分组' }]}
            >
              <Select
                style={{ width: 180 }}
                options={sections.map((value) => ({ label: value, value }))}
              />
            </Form.Item>
            <Form.Item name="category" label="分类">
              <Input />
            </Form.Item>
            <Form.Item name="sortOrder" label="排序">
              <InputNumber min={0} />
            </Form.Item>
          </Space>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item
                name="itemKey"
                label="明细项标识"
                rules={[{ required: true, message: '请输入标识' }]}
              >
                <Input />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="itemName"
                label="明细项名称"
                rules={[{ required: true, message: '请输入名称' }]}
              >
                <Input />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="description" label="功能描述">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="priceDescription" label="价格说明">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Row gutter={12}>
            <Col span={8}>
              <Form.Item
                name="isRequired"
                label="必选"
                valuePropName="checked"
                getValueFromEvent={(checked: boolean) => (checked ? 1 : 0)}
              >
                <Switch checkedChildren="是" unCheckedChildren="否" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="taxRate" label="税率">
                <InputNumber
                    min={0}
                    max={100}
                    precision={2}
                    suffix="%"
                    style={{ width: '100%' }}
                  />
                </Form.Item>
              </Col>
            </Row>
            <Row gutter={12}>
              <Col span={8}>
              <Form.Item
                  name="chargeMethod"
                  label="收费方式"
                  tooltip="按比例计费用于「产品模块总价的 20%/年」这类策略"
                >
                  <Select
                    allowClear
                    options={[
                      { label: '固定单价', value: 'FIXED' },
                      { label: '按次/按个', value: 'PER_UNIT' },
                      { label: '按基数比例', value: 'PERCENT_OF_BASE' },
                    ]}
                  />
                </Form.Item>
              </Col>
            <Col span={8}>
              <Form.Item
                noStyle
                shouldUpdate={(prev, next) =>
                  prev.chargeMethod !== next.chargeMethod
                }
              >
                {({ getFieldValue }) =>
                  getFieldValue('chargeMethod') === 'PERCENT_OF_BASE' ? (
                    <Form.Item name="chargeBaseSection" label="计费基数分组">
                      <Select
                        options={sections.map((value) => ({ label: value, value }))}
                      />
                    </Form.Item>
                  ) : (
                    <Form.Item name="unitPrice" label="未税单价">
                      <InputNumber min={0} precision={2} style={{ width: '100%' }} />
                    </Form.Item>
                  )
                }
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                noStyle
                shouldUpdate={(prev, next) =>
                  prev.chargeMethod !== next.chargeMethod
                }
              >
                {({ getFieldValue }) =>
                  getFieldValue('chargeMethod') === 'PERCENT_OF_BASE' ? (
                    <Form.Item name="chargeRate" label="费率">
                      <InputNumber
                        min={0}
                        max={1}
                        step={0.01}
                        style={{ width: '100%' }}
                        placeholder="0.2 = 20%"
                      />
                    </Form.Item>
                  ) : (
                    <Form.Item name="costPrice" label="成本单价">
                      <InputNumber min={0} precision={2} style={{ width: '100%' }} />
                    </Form.Item>
                  )
                }
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col span={8}>
              <Form.Item
                name="firstYearFree"
                label="首年免费"
                tooltip="勾选后该行不计入首年，只计入次年及以后"
                valuePropName="checked"
                getValueFromEvent={(checked: boolean) => (checked ? 1 : 0)}
              >
                <Switch checkedChildren="是" unCheckedChildren="否" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="chargeUnit" label="收费单位">
                <Input />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
